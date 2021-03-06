/*
 * Copyright 2019 Kroto+ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.marcoferrer.krotoplus.coroutines.client

import com.github.marcoferrer.krotoplus.coroutines.CALL_OPTION_COROUTINE_CONTEXT
import com.github.marcoferrer.krotoplus.coroutines.call.bindScopeCancellationToCall
import com.github.marcoferrer.krotoplus.coroutines.call.newRpcScope
import com.github.marcoferrer.krotoplus.coroutines.withCoroutineContext
import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.stub.AbstractStub
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientCalls.asyncBidiStreamingCall
import io.grpc.stub.ClientCalls.asyncClientStreamingCall
import io.grpc.stub.ClientCalls.asyncServerStreamingCall
import io.grpc.stub.ClientCalls.asyncUnaryCall
import io.grpc.stub.ClientResponseObserver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MESSAGE_CLIENT_CANCELLED_CALL = "Client has cancelled the call"

/**
 * Executes a unary rpc call using the [io.grpc.Channel] and [io.grpc.CallOptions] attached to the
 * receiver [AbstractStub].
 *
 * This method will suspend the invoking coroutine until completion. Its execution is bound to the current
 * [CancellableContinuation] as well as the current [Job]
 *
 * The server is notified of cancellation under one of the following conditions:
 * * The current continuation is, or has become cancelled.
 * * The current job is, or has become cancelled. Either exceptionally or normally.
 *
 * A cancellation of the current scopes job will not always directly correlate to a cancelled continuation.
 * If the job of the receiver stub differs from that of the continuation, its cancellation will cause the
 * this method to throw a [io.grpc.StatusRuntimeException] with a status code of [io.grpc.Status.CANCELLED].
 *
 * @throws io.grpc.StatusRuntimeException The error returned by the server or local scope cancellation.
 *
 */
public suspend fun <ReqT, RespT, T : AbstractStub<T>> T.clientCallUnary(
    request: ReqT,
    method: MethodDescriptor<ReqT, RespT>
): RespT = clientCallUnary(request, method, channel, callOptions)

public suspend fun <ReqT, RespT> clientCallUnary(
    request: ReqT,
    method: MethodDescriptor<ReqT, RespT>,
    channel: io.grpc.Channel,
    callOptions: CallOptions = CallOptions.DEFAULT
): RespT = suspendCancellableCoroutine { cont: CancellableContinuation<RespT> ->

    val initialContext = cont.context + callOptions.getOption(CALL_OPTION_COROUTINE_CONTEXT)
    with(newRpcScope(initialContext, method)) {
        val call = channel.newCall(method, callOptions.withCoroutineContext(coroutineContext))
        asyncUnaryCall<ReqT, RespT>(call, request, SuspendingUnaryObserver(cont))
        cont.invokeOnCancellation { call.cancel(it?.message, it) }
        bindScopeCancellationToCall(call)
    }
}

/**
 * Executes a server streaming rpc call using the [io.grpc.Channel] and [io.grpc.CallOptions] attached to the
 * receiver [AbstractStub].
 *
 * This method will return a [ReceiveChannel] to its invoker so that response messages from the target server can
 * be processed in a suspending manner.
 *
 * A new [observer][ClientResponseObserver] is created with back-pressure enabled. The observer will be
 * used by grpc to handle incoming messages and errors from the target server. New messages are submitted to the
 * resulting channel for consumption by the client.
 *
 * In the event of the server returning an [error][io.grpc.StatusRuntimeException], the resulting [ReceiveChannel] will
 * be closed with it. If the local coroutine scope is cancelled then the resulting [ReceiveChannel] will be closed with
 * a [io.grpc.StatusRuntimeException] with a status code of [io.grpc.Status.CANCELLED]
 *
 * The server is notified of cancellations once the current job is, or has become cancelled,
 * either exceptionally or normally.
 *
 */
public fun <ReqT, RespT, T : AbstractStub<T>> T.clientCallServerStreaming(
    request: ReqT,
    method: MethodDescriptor<ReqT, RespT>
): ReceiveChannel<RespT> =
    clientCallServerStreaming(request, method, channel, callOptions)

public fun <ReqT, RespT> clientCallServerStreaming(
    request: ReqT,
    method: MethodDescriptor<ReqT, RespT>,
    grpcChannel: io.grpc.Channel,
    callOptions: CallOptions = CallOptions.DEFAULT
): ReceiveChannel<RespT> {

    val observerAdapter = ResponseObserverChannelAdapter<ReqT, RespT>()
    val rpcScope = newRpcScope(callOptions.getOption(CALL_OPTION_COROUTINE_CONTEXT), method)
    val responseFlow = callbackFlow<RespT> flow@ {
        observerAdapter.scope = this

        val call = grpcChannel
            .newCall(method, callOptions.withCoroutineContext(coroutineContext))
            .beforeCancellation { message, cause ->
                observerAdapter.beforeCallCancellation(message, cause)
            }

        val job = coroutineContext[Job]!!

        // Start the RPC Call
        asyncServerStreamingCall<ReqT, RespT>(call, request, observerAdapter)

        // If our parent job is cancelled before we can
        // start the call then we need to propagate the
        // cancellation to the underlying call
        job.invokeOnCompletion { error ->
            // Our job can be cancelled after completion due to the inner machinery
            // of kotlinx.coroutines.flow.Channels.kt.emitAll(). Its final operation
            // after receiving a close is a call to channel.cancelConsumed(cause).
            // Even if it doesnt encounter an exception it will cancel with null.
            // We will only invoke cancel on the call
            if(job.isCancelled && observerAdapter.isActive){
                call.cancel(MESSAGE_CLIENT_CANCELLED_CALL, error)
            }
        }

        suspendCancellableCoroutine<Unit> { cont ->
            // Here we need to handle not only parent job cancellation
            // but calls to `channel.cancel(...)` as well.
            cont.invokeOnCancellation { error ->
                if (observerAdapter.isActive) {
                    call.cancel(MESSAGE_CLIENT_CANCELLED_CALL, error)
                }
            }
            invokeOnClose { error ->
                if (error == null)
                    cont.resume(Unit) else
                    cont.resumeWithException(error)
            }
        }
    }

    // Use buffer UNLIMITED so that we dont drop any inbound messages
    return flow { emitAll(responseFlow.buffer(Channel.UNLIMITED)) }
        .onEach {
            if(observerAdapter.isActive){
                observerAdapter.callStreamObserver.request(1)
            }
        }
        // We use buffer RENDEZVOUS on the outer flow so that our
        // `onEach` operator is only invoked each time a message is
        // collected instead of each time a message is received from
        // from the underlying call.
        .buffer(Channel.RENDEZVOUS)
        .produceIn(rpcScope)

}

public fun <ReqT, RespT, T : AbstractStub<T>> T.clientCallBidiStreaming(
    method: MethodDescriptor<ReqT, RespT>
): ClientBidiCallChannel<ReqT, RespT> =
    clientCallBidiStreaming(method, channel, callOptions)

public fun <ReqT, RespT> clientCallBidiStreaming(
    method: MethodDescriptor<ReqT, RespT>,
    channel: io.grpc.Channel,
    callOptions: CallOptions = CallOptions.DEFAULT
): ClientBidiCallChannel<ReqT, RespT> {

    val initialContext = callOptions.getOption(CALL_OPTION_COROUTINE_CONTEXT)
    with(newRpcScope(initialContext, method)) {

        val call = channel.newCall(method, callOptions.withCoroutineContext(coroutineContext))
        val callChannel = ClientBidiCallChannelImpl<ReqT, RespT>(coroutineContext)
        asyncBidiStreamingCall<ReqT, RespT>(call, callChannel)
        bindScopeCancellationToCall(call)

        return callChannel
    }
}

public fun <ReqT, RespT, T : AbstractStub<T>> T.clientCallClientStreaming(
    method: MethodDescriptor<ReqT, RespT>
): ClientStreamingCallChannel<ReqT, RespT> =
    clientCallClientStreaming(method, channel, callOptions)

public fun <ReqT, RespT> clientCallClientStreaming(
    method: MethodDescriptor<ReqT, RespT>,
    channel: io.grpc.Channel,
    callOptions: CallOptions = CallOptions.DEFAULT
): ClientStreamingCallChannel<ReqT, RespT> {
    val initialContext = callOptions.getOption(CALL_OPTION_COROUTINE_CONTEXT)
    with(newRpcScope(initialContext, method)) {
        val call = channel.newCall(method, callOptions.withCoroutineContext(coroutineContext))
        val callChannel = ClientStreamingCallChannelImpl<ReqT, RespT>(coroutineContext)
        asyncClientStreamingCall<ReqT, RespT>(call, callChannel)
        bindScopeCancellationToCall(call)

        return callChannel
    }
}

