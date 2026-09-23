package com.activityassistant.support;

import java.util.UUID;

public final class Ids {
    private Ids() {}

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String accountId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
