package com.aquarius.proxy.exception;

import org.springframework.http.HttpStatus;

/**
 * Excepcion dominio del gateway con el status HTTP que debe devolver al cliente.
 */
public class ProxyException extends RuntimeException {

    private final HttpStatus status;

    public ProxyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ProxyException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
