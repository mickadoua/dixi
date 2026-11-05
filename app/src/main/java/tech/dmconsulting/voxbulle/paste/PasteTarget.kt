package tech.dmconsulting.voxbulle.paste

/**
 * Whether the focused field may receive the dictated text: editable, focused, not a
 * password field, and not one of Voxbulle's own fields (the suggestion panel).
 * Anything else falls back to the clipboard and « Texte copié ».
 */
fun isPasteTarget(
    editable: Boolean,
    focused: Boolean,
    password: Boolean,
    fieldPackage: CharSequence?,
    ownPackage: String,
): Boolean = editable && focused && !password && fieldPackage?.toString() != ownPackage
