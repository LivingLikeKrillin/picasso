package dev.picasso.mimic

// 음성 케이스 전용. 기종 좌표로 분기하는 출하 코드다.
internal fun gaitOf(robotId: String): Int = if (robotId == "humanoid-a") 2 else 4
