package com.example.messaging_service.provider.generic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GsmCharsetTest {

	@Test
	void detectsGsmAndUnicodeText() {
		assertThat(GsmCharset.isGsm7("Your code is 482913. Cost: £5 {ok} €")).isTrue();
		assertThat(GsmCharset.isGsm7("नमस्ते")).isFalse();
		assertThat(GsmCharset.isGsm7("Thanks 🙂")).isFalse();
	}

}
