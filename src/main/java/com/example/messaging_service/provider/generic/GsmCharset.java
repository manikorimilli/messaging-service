package com.example.messaging_service.provider.generic;

/**
 * The GSM 03.38 alphabet (basic and extension tables) that a normal SMS can carry. Text with any
 * other character (Hindi, emoji, ...) must be sent in Unicode mode.
 */
final class GsmCharset {

	private static final String GSM_7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
			+ "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà" + "\f^{}\\[~]|€";

	private GsmCharset() {
	}

	static boolean isGsm7(String text) {
		return text.codePoints().allMatch(c -> GSM_7.indexOf(c) >= 0);
	}

}
