package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class EmailProvider(val code: String) {
    RESEND("resend"),
    SENDGRID("sendgrid"),
    SES("ses"),
    POSTMARK("postmark"),
    SMTP("smtp");

    companion object {
        fun fromCode(code: String): EmailProvider? =
            entries.firstOrNull { it.code == code }
    }
}
