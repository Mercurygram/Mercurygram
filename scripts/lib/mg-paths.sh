# Shared definitions of MG/TF-owned source paths.
# Source this file from any script that needs to exclude Mercurygram's own
# code when diffing against upstream.
#
# Usage:
#   . "$(git rev-parse --show-toplevel)/scripts/lib/mg-paths.sh"
#   # MG_OWNED_PATHS and MG_HOOK_FILES are now defined.

# Java packages that contain ONLY MG/TF code. Diff scripts skip these
# entirely so upstream-derived patches don't try to delete or modify them.
MG_OWNED_PATHS=(
    'TMessagesProj/src/main/java/it/belloworld/mercurygram/'
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/'
)

# Upstream files that carry small MG/TF hooks (declared as a constant so the
# hook block can be located by line number across rebases).
MG_HOOK_FILES=(
    'TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java'
)
