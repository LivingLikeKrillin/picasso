package dev.picasso.mimic.transport

import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver

/**
 * 단항 RPC 하나를 응답한다.
 *
 * **`StatusRuntimeException`을 그냥 던지면 안 된다.** grpc-java는 핸들러에서
 * 새어 나온 예외를 종류와 무관하게 `UNKNOWN`으로 접는다(실측: `NOT_FOUND`를
 * 던졌는데 클라이언트가 `UNKNOWN`을 받는다). 그러면 라우팅 실패와 서버 버그가
 * 소비자에게 같은 것으로 보인다.
 */
inline fun <T> reply(observer: StreamObserver<T>, block: () -> T) {
    try {
        observer.onNext(block())
        observer.onCompleted()
    } catch (e: StatusRuntimeException) {
        observer.onError(e)
    }
}
