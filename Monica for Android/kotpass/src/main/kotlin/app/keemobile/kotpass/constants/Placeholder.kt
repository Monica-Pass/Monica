package app.keemobile.kotpass.constants

internal enum class Placeholder(val value: String) {
    Title("TITLE"),
    UserName("USERNAME"),
    Password("PASSWORD"),
    Url("URL"),
    Notes("NOTES"),
    Uuid("UUID"),
    Reference("REF:"),
    CustomField("S:");

    operator fun invoke() = this.value
}
