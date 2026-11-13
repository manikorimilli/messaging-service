package com.example.messaging_service.provider.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.provider.OutboundMessage;

class TemplateFillerTest {

	private static final OutboundMessage MESSAGE = new OutboundMessage(MessageType.SMS, "+910000000001", "Hello");

	private final Map<String, String> values = TemplateFiller.values(MESSAGE,
			Map.of("account-sid", "AC123", "from", "+15551234567"));

	@Test
	void fillsBuiltInsAndVariables() {
		assertThat(TemplateFiller.fill("{{to}}", values)).isEqualTo("+910000000001");
		assertThat(TemplateFiller.fill("{{to_digits}}", values)).isEqualTo("910000000001");
		assertThat(TemplateFiller.fill("{{text}}", values)).isEqualTo("Hello");
		assertThat(TemplateFiller.fill("{{from}}", values)).isEqualTo("+15551234567");
		assertThat(TemplateFiller.fill("https://x/Accounts/{{account-sid}}/Messages.json", values))
			.isEqualTo("https://x/Accounts/AC123/Messages.json");
	}

	@Test
	void allowsSpacesInsideBraces() {
		assertThat(TemplateFiller.fill("{{ to }}", values)).isEqualTo("+910000000001");
	}

	@Test
	void unknownPlaceholderFails() {
		assertThatThrownBy(() -> TemplateFiller.fill("{{api_key}}", values))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unknown placeholder {{api_key}}");
	}

	@Test
	void messageTextIsNeverExpandedAgain() {
		Map<String, String> tricky = TemplateFiller.values(
				new OutboundMessage(MessageType.SMS, "+910000000001", "Pay $5 {{account-sid}} \\ now"),
				Map.of("account-sid", "AC123"));

		assertThat(TemplateFiller.fill("{{text}}", tricky)).isEqualTo("Pay $5 {{account-sid}} \\ now");
	}

	@Test
	void listsPlaceholders() {
		assertThat(TemplateFiller.placeholders("{{to}} and {{ from }} and {{to}}")).containsExactly("to", "from");
		assertThat(TemplateFiller.placeholders("{from} {uaap.message.text} {{to}} ${HOST}"))
			.containsExactly("from", "uaap.message.text", "to");
	}

	@Test
	void fillsSingleBracePlaceholders() {
		assertThat(TemplateFiller.fill("{from} to {to}", values)).isEqualTo("+15551234567 to +910000000001");
		assertThat(TemplateFiller.fill("https://x/Accounts/{account-sid}/Messages.json", values))
			.isEqualTo("https://x/Accounts/AC123/Messages.json");
	}

	@Test
	void fillsUaapNamesFromTheMessage() {
		assertThat(TemplateFiller.fill("{uaap.message.to}", values)).isEqualTo("+910000000001");
		assertThat(TemplateFiller.fill("{{uaap.message.to_digits}}", values)).isEqualTo("910000000001");
		assertThat(TemplateFiller.fill("{uaap.message.text}", values)).isEqualTo("Hello");
		assertThat(TemplateFiller.fill("{uaap.message.template}", values)).isEqualTo("Hello");
		assertThat(TemplateFiller.isMessageValue("uaap.message.template")).isTrue();
		assertThat(TemplateFiller.isMessageValue("uaap.message.other")).isFalse();
	}

	@Test
	void leavesEnvironmentPlaceholdersAlone() {
		assertThat(TemplateFiller.fill("${HOST}/{to}", values)).isEqualTo("${HOST}/+910000000001");
	}

	@Test
	void unknownSingleBraceOrUaapPlaceholderFails() {
		assertThatThrownBy(() -> TemplateFiller.fill("{api_key}", values)).hasMessage("Unknown placeholder {api_key}");
		assertThatThrownBy(() -> TemplateFiller.fill("{uaap.message.other}", values))
			.hasMessage("Unknown placeholder {uaap.message.other}");
	}

}
