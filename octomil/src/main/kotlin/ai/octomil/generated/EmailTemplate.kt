package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class EmailTemplate(val code: String) {
    PASSWORD_RESET("password_reset"),
    EMAIL_VERIFICATION("email_verification"),
    WELCOME("welcome"),
    STATUS_SUBSCRIPTION_VERIFICATION("status_subscription_verification"),
    INCIDENT_NOTIFICATION("incident_notification"),
    INCIDENT_RESOLUTION("incident_resolution"),
    TEAM_INVITATION("team_invitation"),
    API_KEY_EXPIRY_WARNING("api_key_expiry_warning");

    companion object {
        fun fromCode(code: String): EmailTemplate? =
            entries.firstOrNull { it.code == code }
    }
}
