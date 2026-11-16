package com.flashticket.domain.exception;

/**
 * Exceção lançada quando falham validações de autenticação (JWT inválido/expirado,
 * roubo/reuso de Refresh Token detectado, PKCE inválido ou Client Credentials incorretas).
 */
public class SecurityAuthenticationException extends RuntimeException {

    public SecurityAuthenticationException(String message) {
        super(message);
    }
}
