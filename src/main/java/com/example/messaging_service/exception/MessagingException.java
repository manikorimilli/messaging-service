package com.example.messaging_service.exception;

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
