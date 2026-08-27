package com.ecomagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PII 脱敏单测（§5 三道缝统一入口）。
 */
class PiiMaskUtilTest {

    @Test
    void masksPhone() {
        assertEquals("我的手机是138****8000", PiiMaskUtil.mask("我的手机是13812348000"));
    }

    @Test
    void masksIdCard() {
        assertEquals("身份证110101********1234", PiiMaskUtil.mask("身份证110101199001011234"));
    }

    @Test
    void masksEmail() {
        assertEquals("联系 a***@example.com", PiiMaskUtil.mask("联系 a@example.com"));
    }

    @Test
    void masksBankCard() {
        String masked = PiiMaskUtil.mask("卡号 6222021234567890");
        assertFalse(masked.contains("1234567890"), "银行卡中间位应被打码: " + masked);
    }

    @Test
    void nullAndBlankPassthrough() {
        assertNull(PiiMaskUtil.mask(null));
        assertEquals("  ", PiiMaskUtil.mask("  "));
    }
}
