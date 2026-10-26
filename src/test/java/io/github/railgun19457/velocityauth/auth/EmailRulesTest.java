package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class EmailRulesTest {

    private static final VelocityAuthConfig.Domain WHITELIST_QQ =
            new VelocityAuthConfig.Domain(VelocityAuthConfig.Domain.Mode.WHITELIST, List.of("qq.com"));
    private static final VelocityAuthConfig.Domain BLACKLIST =
            new VelocityAuthConfig.Domain(VelocityAuthConfig.Domain.Mode.BLACKLIST, List.of("tempmail.com"));
    private static final VelocityAuthConfig.Domain OFF =
            new VelocityAuthConfig.Domain(VelocityAuthConfig.Domain.Mode.OFF, List.of());

    @Test
    void syntaxValidation() {
        assertTrue(EmailRules.isValid("user@qq.com"));
        assertTrue(EmailRules.isValid("a.b+c-d_e@sub.example.org"));
        assertFalse(EmailRules.isValid("no-at-sign"));
        assertFalse(EmailRules.isValid("user@localhost"));
        assertFalse(EmailRules.isValid("user@@qq.com"));
        assertFalse(EmailRules.isValid(""));
        assertFalse(EmailRules.isValid(null));
    }

    @Test
    void whitelistUsesExactDomainMatch() {
        assertEquals(EmailRules.DomainVerdict.ALLOWED, EmailRules.check("a@qq.com", WHITELIST_QQ));
        assertEquals(EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED, EmailRules.check("a@mail.qq.com", WHITELIST_QQ));
        assertEquals(EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED, EmailRules.check("a@qq.com.evil.com", WHITELIST_QQ));
        assertEquals(EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED, EmailRules.check("a@gmail.com", WHITELIST_QQ));
    }

    @Test
    void blacklistRejectsListedDomain() {
        assertEquals(EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED, EmailRules.check("a@tempmail.com", BLACKLIST));
        assertEquals(EmailRules.DomainVerdict.ALLOWED, EmailRules.check("a@qq.com", BLACKLIST));
    }

    @Test
    void offAllowsEverythingValid() {
        assertEquals(EmailRules.DomainVerdict.ALLOWED, EmailRules.check("a@anything.io", OFF));
        assertEquals(EmailRules.DomainVerdict.INVALID_EMAIL, EmailRules.check("bad", OFF));
    }

    @Test
    void normalizeLowercasesAndTrims() {
        assertEquals("a@qq.com", EmailRules.normalize("  A@QQ.Com "));
        assertEquals("qq.com", EmailRules.domainOf("User@QQ.com"));
    }
}
