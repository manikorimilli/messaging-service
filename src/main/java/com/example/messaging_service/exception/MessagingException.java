package com.example.messaging_service.exception;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.web.client.ResourceAccessException;

/**
 * The single exception the service and the provider adapters throw. The message is the
 * human-readable detail returned to the client, so it must never contain credentials.
 */
public class MessagingException extends RuntimeException {

	private final ErrorCode code;

	/** Provider id (e.g. "TWILIO"), or null when no provider is involved. */
	private final String provider;

	/** The provider's own error code (e.g. Twilio "21211"), or null. */
	private final String providerErrorCode;

	public MessagingException(ErrorCode code, String provider, String detail) {
		this(code, provider, null, detail, null);
	}

	public MessagingException(ErrorCode code, String provider, String providerErrorCode, String detail) {
		this(code, provider, providerErrorCode, detail, null);
	}

	public MessagingException(ErrorCode code, String provider, String providerErrorCode, String detail,
			Throwable cause) {
		super(detail, cause);
		this.code = code;
		this.provider = provider;
		this.providerErrorCode = providerErrorCode;
	}

	/**
	 * Maps a network failure from RestClient (JDK HttpClient underneath). Could not connect means
	 * the message was not sent: PROVIDER_UNAVAILABLE. Connected but no reply in time means the
	 * outcome is unknown: PROVIDER_TIMEOUT.
	 */
	public static MessagingException fromNetworkFailure(String provider, ResourceAccessException e) {
		// HttpConnectTimeoutException extends HttpTimeoutException, so connect failures are checked first.
		if (hasCause(e, ConnectException.class) || hasCause(e, HttpConnectTimeoutException.class)
				|| hasCause(e, UnknownHostException.class)) {
			return new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, provider, null,
					"Could not connect to provider '%s'. The message was not sent.".formatted(provider), e);
		}
		if (hasCause(e, HttpTimeoutException.class) || hasCause(e, SocketTimeoutException.class)) {
			return new MessagingException(ErrorCode.PROVIDER_TIMEOUT, provider, null,
					"Provider '%s' did not respond in time. The message may or may not have been sent; check before resending."
						.formatted(provider),
					e);
		}
		return new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, provider, null,
				"Network error while calling provider '%s'.".formatted(provider), e);
	}

	private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	public ErrorCode code() {
		return code;
	}

	public String provider() {
		return provider;
	}

	public String providerErrorCode() {
		return providerErrorCode;
	}

}
