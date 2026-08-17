package dev.klaiber.cirrus.ui.markdown.math

/**
 * The LaTeX commands that actually turn up in model output, and what they mean.
 *
 * Shared by the typesetter and by the plain-text fallback (`renderMathToUnicode`), so a symbol
 * only ever has to be added in one place. Anything absent degrades to its own name without the
 * backslash, which is still more readable than the source.
 */
internal val MATH_SYMBOLS: Map<String, String> = mapOf(
    // Relations
    "approx" to "≈", "neq" to "≠", "ne" to "≠", "leq" to "≤", "le" to "≤",
    "geq" to "≥", "ge" to "≥", "equiv" to "≡", "sim" to "∼", "simeq" to "≃",
    "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫", "asymp" to "≍",
    "doteq" to "≐", "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰",
    "models" to "⊨", "vdash" to "⊢", "perp" to "⊥", "mid" to "∣",
    // Operators
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
    "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "•", "oplus" to "⊕",
    "ominus" to "⊖", "otimes" to "⊗", "odot" to "⊙", "wedge" to "∧", "vee" to "∨",
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
    "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
    "bigoplus" to "⨁", "bigotimes" to "⨂", "bigvee" to "⋁", "bigwedge" to "⋀",
    "partial" to "∂", "nabla" to "∇", "infty" to "∞", "surd" to "√",
    // Sets and logic
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
    "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
    "emptyset" to "∅", "varnothing" to "∅", "setminus" to "∖",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄",
    "land" to "∧", "lor" to "∨", "lnot" to "¬", "neg" to "¬",
    "therefore" to "∴", "because" to "∵",
    // Arrows
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
    "longrightarrow" to "⟶", "longleftarrow" to "⟵", "hookrightarrow" to "↪",
    "mapsto" to "↦", "implies" to "⇒", "iff" to "⇔", "uparrow" to "↑", "downarrow" to "↓",
    // Greek, lower case
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Greek, upper case
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω",
    // Punctuation and spacing
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
    "quad" to "  ", "qquad" to "    ", "space" to " ",
    "angle" to "∠", "parallel" to "∥", "degree" to "°",
    "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ",
    "checkmark" to "✓", "dagger" to "†", "flat" to "♭", "sharp" to "♯",
)

/** Blackboard bold, for the number sets that turn up in complexity and probability answers. */
internal val MATH_BLACKBOARD: Map<String, String> = mapOf(
    "R" to "ℝ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "C" to "ℂ", "P" to "ℙ", "E" to "𝔼",
    "H" to "ℍ", "F" to "𝔽", "1" to "𝟙",
)

/**
 * Which spacing class each symbol belongs to.
 *
 * Anything unlisted is ordinary, which is the right default: getting a relation wrong costs a few
 * points of space, while treating everything as ordinary costs the formula its structure.
 */
internal val MATH_ATOM_CLASSES: Map<String, Atom> = buildMap {
    val relations = listOf(
        "=", "≈", "≠", "≤", "≥", "≡", "∼", "≃", "≅", "∝", "≪", "≫", "≍", "≐",
        "<", ">", "≺", "≻", "⪯", "⪰", "⊨", "⊢", "⊥", "∣", "∈", "∉", "∋",
        "⊂", "⊃", "⊆", "⊇", "→", "←", "↔", "⇒", "⇐", "⇔", "⟶", "⟵", "↪", "↦",
        "↑", "↓", "∴", "∵", ":=",
    )
    val binaries = listOf(
        "+", "−", "×", "÷", "±", "∓", "·", "∗", "⋆", "∘", "•", "⊕", "⊖", "⊗", "⊙",
        "∧", "∨", "∪", "∩", "∖", "⊎",
    )
    relations.forEach { put(it, Atom.REL) }
    binaries.forEach { put(it, Atom.BIN) }
    listOf(",", ";").forEach { put(it, Atom.PUNCT) }
    listOf("(", "[", "{", "⟨", "⌊", "⌈").forEach { put(it, Atom.OPEN) }
    listOf(")", "]", "}", "⟩", "⌋", "⌉").forEach { put(it, Atom.CLOSE) }
    listOf("∑", "∏", "∐", "∫", "∬", "∭", "∮", "⋃", "⋂", "⨁", "⨂", "⋁", "⋀")
        .forEach { put(it, Atom.OP) }
}

/**
 * Functions are set upright — `\sin x` is a name, not a product of three variables — and behave
 * as operators for spacing so the argument is not glued to them.
 */
internal val MATH_FUNCTIONS: Set<String> = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "det", "dim",
    "gcd", "lcm", "hom", "ker", "deg", "arg", "Pr", "min", "max", "sup", "inf",
    "lim", "limsup", "liminf", "mod", "bmod",
)

/** Operators that grow in display style and carry their limits above and below. */
internal val MATH_BIG_OPERATORS: Set<String> = setOf(
    "sum", "prod", "coprod", "bigcup", "bigcap", "bigoplus", "bigotimes", "bigvee", "bigwedge",
)

/** Integrals grow too, but their limits stay at the side, as they do in print. */
internal val MATH_INTEGRALS: Set<String> = setOf("int", "iint", "iiint", "oint")

/** Delimiters that `\left` and `\right` may stretch, keyed by their LaTeX spelling. */
internal val MATH_DELIMITERS: Map<String, String> = mapOf(
    "(" to "(", ")" to ")", "[" to "[", "]" to "]",
    "\\{" to "{", "\\}" to "}", "{" to "{", "}" to "}",
    "|" to "|", "\\|" to "‖", "\\vert" to "|", "\\Vert" to "‖",
    "\\langle" to "⟨", "\\rangle" to "⟩",
    "\\lfloor" to "⌊", "\\rfloor" to "⌋",
    "\\lceil" to "⌈", "\\rceil" to "⌉",
    "\\lbrace" to "{", "\\rbrace" to "}",
    "/" to "/", "\\backslash" to "\\",
    // `\left.` is the invisible delimiter: one side of the fence with nothing drawn.
    "." to "",
)
