package dev.screencopy.sender

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal fun fixturePath(name: String): Path =
    System.getProperty("fixtures")?.let { Path.of(it, name) }
        ?: Path.of(checkNotNull(object {}.javaClass.classLoader?.getResource(name)) { "Missing fixture $name" }.toURI())

// SDK36 exposes readAllBytes but not the JDK's Files.readString API.
internal fun readUtf8Strict(path: Path): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
    .toString()
