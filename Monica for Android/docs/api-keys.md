# AI API Keys

Create an API Key from the password editor's type menu. The form stores a provider name, optional official website, the key, optional API request URL, and notes. Names and endpoints are freeform so official providers, self-hosted models and proxy services use the same flow. No network request is made to test or transmit the key.

The entry uses the existing password infrastructure for local storage, MDBX2, KeePass and Bitwarden. The provider name is the title, the official website is the standard URL, and the secret is the encrypted password. The `API_KEY` login type distinguishes keys from login credentials. Public custom fields carry `monica_api_key_type=API_KEY` and the optional `monica_api_key_url`. KeePass and Bitwarden recognize the marker on download. Edits preserve unrelated entry data and custom fields.

Keys are hidden by default. Copy uses Monica's sensitive clipboard behavior. Editor state is memory-only and clears on vault lock; visibility resets when the app goes into the background. API Keys are excluded from login autofill and the login keyboard picker. Two keys from the same provider retain separate detail and editor links.

The existing native MDBX API Token feature remains available for gateway/CLI credentials; its payload and compatibility rules are unchanged.

See [editable M3E design](design/ai-api-key-m3e.md) and [verification](testing/ai-api-key.md).
