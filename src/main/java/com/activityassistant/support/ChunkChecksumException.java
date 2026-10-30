package com.activityassistant.support;

/** 分片内容与客户端声明的 SHA-256 不一致，需要客户端重传该分片。 */
public class ChunkChecksumException extends RuntimeException {
    private final int chunkIndex;
    private final String expected;
    private final String actual;

    public ChunkChecksumException(int chunkIndex, String expected, String actual) {
        super("分片 " + chunkIndex + " 校验失败，请重新上传该分片");
        this.chunkIndex = chunkIndex;
        this.expected = expected;
        this.actual = actual;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public String expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }
}
