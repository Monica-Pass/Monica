package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.data.passwordProjectKey

/** Project membership is independent of optional stacks between different projects. */
internal fun VaultV2Item.passwordProjectIdentity(): String? =
    passwordEntry?.takeIf { type == VaultV2ItemType.PASSWORD && nativeToken == null }?.passwordProjectKey()

internal fun VaultV2Item.projectDisplayIdentity(): String =
    passwordProjectIdentity()?.let { "project:$it" } ?: "item:$key"

/** Call after filtering/sorting: a match in any password remains a visible project. */
internal fun collapseVaultV2PasswordProjects(items: List<VaultV2Item>): List<VaultV2Item> =
    items.distinctBy { it.projectDisplayIdentity() }

/** Keep all original rows for actions; a representative card must never drop sibling passwords. */
internal fun expandVaultV2ProjectSelection(
    selected: List<VaultV2Item>,
    availableItems: List<VaultV2Item>,
): List<VaultV2Item> {
    val keys = selected.mapTo(hashSetOf()) { it.key }
    val projects = selected.mapNotNullTo(hashSetOf()) { it.passwordProjectIdentity() }
    return availableItems.filter { it.key in keys || it.passwordProjectIdentity()?.let(projects::contains) == true }
        .distinctBy { it.key }
}
