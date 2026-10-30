package com.activityassistant.support;

import java.util.List;

/** 合并时仍有分片未上传。 */
public class MissingChunksException extends RuntimeException {
    private final List<Integer> missing;

    public MissingChunksException(List<Integer> missing) {
        super("还有 " + missing.size() + " 个分片未上传：" + missing);
        this.missing = List.copyOf(missing);
    }

    public List<Integer> missing() {
        return missing;
    }
}
