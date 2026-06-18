import tseslint from "typescript-eslint";

export default tseslint.config(
    // ── Global ignores ─────────────────────────────────────────────
    { ignores: ["out/**", "dist/**", "**/*.d.ts"] },

    // ── Recommended rule-sets (type-aware) ─────────────────────────
    ...tseslint.configs.recommendedTypeChecked,
    ...tseslint.configs.stylisticTypeChecked,

    // ── Project-wide overrides ─────────────────────────────────────
    {
        files: ["**/*.ts"],
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: "module",
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
        rules: {
            // ── Naming ──────────────────────────────────────────────
            "@typescript-eslint/naming-convention": ["warn", {
                selector: "import",
                format: ["camelCase", "PascalCase"],
            }],

            // ── Style ───────────────────────────────────────────────
            curly: "warn",
            eqeqeq: "warn",
            semi: "warn",

            // ── Type-safety ─────────────────────────────────────────
            "@typescript-eslint/no-floating-promises": "error",
            "@typescript-eslint/no-misused-promises": "error",
            "@typescript-eslint/await-thenable": "error",
            "@typescript-eslint/require-await": "error",
            "@typescript-eslint/no-unnecessary-type-assertion": "warn",
            "@typescript-eslint/no-redundant-type-constituents": "warn",

            // ── Safety ──────────────────────────────────────────────
            "@typescript-eslint/no-unused-vars": ["warn", {
                argsIgnorePattern: "^_",
                varsIgnorePattern: "^_",
            }],
            "@typescript-eslint/only-throw-error": "error",
            "@typescript-eslint/no-explicit-any": "warn",

            // ── Relax rules that conflict with VS Code API patterns ─
            "@typescript-eslint/no-empty-function": "off",
            "@typescript-eslint/prefer-nullish-coalescing": "off",
        },
    }
);