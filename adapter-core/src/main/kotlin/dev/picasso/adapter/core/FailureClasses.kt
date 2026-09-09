package dev.picasso.adapter.core

import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault

/**
 * 어댑터가 실패를 계약의 [Fault] 로 옮기는 한 자리.
 *
 * ## 규칙 (미들웨어 중앙 설계 §1.4, ADR 33)
 *
 * 벤더 코드에서 정준 분류로 옮기는 것은 **어댑터**다 — 기종을 아는 유일한 자리다.
 * 상류와 실행 층은 `failure_class` 로만 분기하고, 벤더 원문(코드·상태 이름·메시지)은
 * `vendor_detail` 에 **동반**한다. 로그와 사후 분석의 것이지 분기의 입력이 아니다.
 * 이 둘을 한 필드에 접으면 상류가 기종별 코드를 다시 배우게 되고(보고서 16장이
 * 감추라는 그것), 나누지 않고 분류만 보내면 현장이 로그에서 원인을 못 찾는다.
 *
 * ## 못 가르면 더 거친 분류로
 *
 * 벤더가 분류할 정보를 안 줬으면 `UNCLASSIFIED` 다 — 어댑터가 지어낸 분류가 로봇의
 * 판정으로 읽히는 것이 가장 나쁘다(§15.65 와 같은 판단: 등급이 낮으면 `NO` 가 아니라
 * `UNKNOWN`). Digit 의 `failure` 를 액션 종류로 `GRASP_FAILED`/`PLACE_FAILED`/
 * `ROUTE_BLOCKED` 까지만 가르고 그 밖은 접지 않는 것이 그 예다.
 *
 * ## 여기 없는 것
 *
 * `active_until` 을 정하지 않는다. 그것은 결함의 수명이고 어댑터마다 관측 방식이 달라
 * 호출자가 정한다 — 기본값(`UNSPECIFIED`)이 남는 것은 앞 판의 어댑터 결함들과 같다.
 */
fun classifiedFault(
    errorType: String,
    failureClass: FailureClass,
    vendorDetail: String,
    errorHint: String,
    canContinueCurrentTask: Boolean = false,
    canAcceptNewTask: Boolean = true,
): Fault = Fault.newBuilder()
    .setErrorType(errorType)
    .setFailureClass(failureClass)
    .setVendorDetail(vendorDetail)
    .setErrorHint(errorHint)
    .setCanContinueCurrentTask(canContinueCurrentTask)
    .setCanAcceptNewTask(canAcceptNewTask)
    .build()
