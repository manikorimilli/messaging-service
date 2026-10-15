package com.example.messaging_service.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class MessageTypeTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@ParameterizedTest
	@ValueSource(strings = { "SMS", "sms", "Sms", " sms " })
	void acceptsAnyLetterCase(String value) {
		assertThat(MessageType.from(value)).isEqualTo(MessageType.SMS);
	}

	@Test
	void nullStaysNullSoNotNullValidationReportsIt() {
		assertThat(MessageType.from(null)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "EMAIL", "", "  " })
	void rejectsUnknownValues(String value) {
		assertThatThrownBy(() -> MessageType.from(value)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("type must be one of [SMS]");
	}

	@Test
	void jacksonUsesTheCreatorForLowercaseJson() {
		assertThat(mapper.readValue("\"sms\"", MessageType.class)).isEqualTo(MessageType.SMS);
	}

	@Test
	void jacksonFailsOnUnknownJsonValue() {
		assertThatThrownBy(() -> mapper.readValue("\"WHATSAPP\"", MessageType.class))
			.isInstanceOf(JacksonException.class)
			.rootCause()
			.hasMessage("type must be one of [SMS]");
	}

}
