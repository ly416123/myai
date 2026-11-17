package com.example.agent.configuration;

import java.util.regex.Pattern;

/** Conservative guard for obvious credential literals; credentials must remain secret references. */
final class ConfigurationSecretGuard {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?is)(?:\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}|\\bsk-[A-Za-z0-9_-]{8,}|"
                    + "(?:api[_ -]?key|password|client[_ -]?secret|access[_ -]?token)\\s*[:=]\\s*[^\\s,;]{8,}|"
                    + "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)");
    private ConfigurationSecretGuard() { }
    static void rejectCredentialLiteral(String value, String field) {
        if (value != null && CREDENTIAL.matcher(value).find())
            throw new IllegalArgumentException(field + " contains credential-like content; use a secret reference");
    }
}
