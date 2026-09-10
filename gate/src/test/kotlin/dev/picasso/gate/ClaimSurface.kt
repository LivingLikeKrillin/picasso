package dev.picasso.gate

import java.nio.file.Path

object ClaimSurface {

    /**
     * 주장의 자리 — **손으로 안 적는다.** `:gate:test` 의 선언된 입력에서 고른다.
     *
     * 실측(2026-09-10): 선언된 입력 아래의 `.md` 는 루트 `README.md` · `docs/` 아래 전부 ·
     * `<모듈>/README.md` 뿐이다. 그래서 거르는 것은 계획 문서뿐이며, **새 문서가 생기면 저절로
     * 자리가 된다.**
     *
     * ★**Kotlin 은 블록 주석이 중첩된다**(Java 와 다르다). 그래서 주석 안에 `/` 바로 뒤 `*` 가 오는
     * 글자를 쓰면 그 자리가 중첩 주석의 시작이 되어 파일 끝까지 안 닫힌다. 이 KDoc 이 실제로 한 번
     * 그렇게 깨졌다 — 글롭을 적으려다가.
     */
    fun documents(): List<Path> =
        Repo.declaredFiles()
            .filter { it.fileName.toString().endsWith(".md") }
            .filterNot { relative(it).startsWith("docs/superpowers/plans/") }

    /** ★`normalize()` 를 뺀 적이 있다. `Repo.root` 는 `…/gate/..` 꼴이라 이 값이 **의미로** 쓰이는
     *  첫 자리가 여기다(경로 접두 판정·`== "README.md"`). JDK 세부에 매달리지 않는다. */
    fun relative(path: Path): String =
        Repo.root.normalize().relativize(path.normalize()).joinToString("/")
}
