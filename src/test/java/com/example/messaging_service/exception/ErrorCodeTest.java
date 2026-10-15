package com.example.messaging_service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeTest {

	// The table from design section 5.7. Clients rely on these statuses.
	@ParameterizedTest
	@CsvSource({
			"VALIDATION_FAILED, 400",
			"UNSUPPORTED_PROVIDER, 400",
			"UNSUPPORTED_MESSAGE_TYPE, 400",
			"PROVIDER_UNAVAILABLE, 503",
			"PROVIDER_TIMEOUT, 504",
			"PROVIDER_REJECTED, 502",
			"INTERNAL_ERROR, 500" })
	void mapsEachCodeToItsHttpStatus(ErrorCode code, int status) {
		assertThat(code.status().value()).isEqualTo(status);
	}

	@Test
	void exceptionCarriesCodeProviderAndProviderErrorCode() {
		MessagingException ex = new MessagingException(ErrorCode.PROVIDER_REJECTED, "TWILIO", "21211", "detail");

		assertThat(ex.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
		assertThat(ex.provider()).isEqualTo("TWILIO");
		assertThat(ex.providerErrorCode()).isEqualTo("21211");
		assertThat(ex.getMessage()).isEqualTo("detail");
	}

}
