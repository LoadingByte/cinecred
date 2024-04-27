package com.loadingbyte.cinecred.imaging

import org.w3c.dom.Element
import org.w3c.dom.Node


object CSS {

    data class Ruleset(val selectors: List<Selector>, val declarations: List<Declaration>)
    data class Declaration(val property: String, val value: String, val important: Boolean)

    data class Selector(val simpleSelectors: List<SimpleSelector>, val combinators: List<Combinator>) {

        val specificity: Int

        init {
            var s = 0
            for (simple in simpleSelectors) {
                if (simple.type != "*") s += 1
                if (simple.id != null) s += 1 shl 16
                s += (simple.classes.size + simple.pseudoClasses.size + simple.attributeConditions.size) shl 8
            }
            specificity = s
        }

        fun matches(element: Element): Boolean {
            var simpleIdx = simpleSelectors.lastIndex
            var underTest = element
            while (true) {
                if (simpleSelectors[simpleIdx].matches(underTest))
                    if (simpleIdx == 0)
                        return true
                    else if (combinators[--simpleIdx] == Combinator.ADJACENT) {
                        var prev: Node = underTest
                        do {
                            prev = prev.previousSibling ?: return false
                        } while (prev !is Element)
                        underTest = prev
                    } else
                        underTest = underTest.parentNode as? Element ?: return false
                else
                    if (simpleIdx == simpleSelectors.lastIndex || combinators[simpleIdx] != Combinator.DESCENDANT)
                        return false
                    else
                        underTest = underTest.parentNode as? Element ?: return false
            }
        }

    }

    enum class Combinator { DESCENDANT, CHILD, ADJACENT }

    data class SimpleSelector(
        val type: String,
        val id: String?,
        val classes: List<String>,
        val pseudoClasses: List<String>,
        val attributeConditions: List<AttributeCondition>
    ) {

        fun matches(element: Element): Boolean {
            if (type != "*" && !type.equals(element.localName, ignoreCase = true))
                return false
            if (id != null && !id.equals(element.getAttribute("id"), ignoreCase = true))
                return false
            if (classes.isNotEmpty()) {
                val attr = element.getAttribute("class")
                if (attr.isEmpty())
                    return false
                val avail = attr.split(' ', '\t')
                if (classes.any { cl -> avail.none { it.equals(cl, ignoreCase = true) } })
                    return false
            }
            if (pseudoClasses.any { it.equals("first-child", ignoreCase = true) } && element.previousSibling != null)
                return false
            if (attributeConditions.isNotEmpty())
                for (cond in attributeConditions) {
                    val attr = element.getAttribute(cond.attribute)
                    when (cond.matcher) {
                        null ->
                            if (attr.isEmpty())
                                return false

                        AttributeMatcher.EQUALS ->
                            if (!attr.equals(cond.value, ignoreCase = true))
                                return false

                        AttributeMatcher.INCLUDES ->
                            if (attr.split(' ', '\t').none { it.contains(cond.value!!, ignoreCase = true) })
                                return false

                        AttributeMatcher.DASH_MATCH ->
                            if (!attr.startsWith(cond.value!!, ignoreCase = true) ||
                                attr.length != cond.value.length && cond.value[attr.length] != '-'
                            ) return false
                    }
                }
            return true
        }

    }

    data class AttributeCondition(val attribute: String, val matcher: AttributeMatcher?, val value: String?)
    enum class AttributeMatcher { EQUALS, INCLUDES, DASH_MATCH }


    fun parseDeclarations(inlineStyle: String): List<Declaration> =
        parseDeclarations(normalize(inlineStyle), -1).result

    fun parseStylesheet(stylesheet: String): List<Ruleset> {
        val rulesets = mutableListOf<Ruleset>()
        val text = normalize(stylesheet)
        var i = -1
        while (++i < text.length)
            when (text[i]) {
                ' ' -> {}
                '@' -> i = findEndOfAtRule(text, i)
                else -> {
                    val selsResult = parseSelectors(text, i)
                    val declsResult = parseDeclarations(text, selsResult.end)
                    i = declsResult.end
                    if (declsResult.result.isNotEmpty())
                        rulesets += Ruleset(selsResult.result, declsResult.result)
                }
            }
        return rulesets
    }

    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var s = 0
        var i = -1
        while (++i < text.length)
            when (val c = text[i]) {
                // Outside of strings, replace tabs and newlines with spaces.
                '\t', '\r', '\n', '\u000C' -> {
                    out.append(text, s, i).append(' ')
                    s = i + 1
                }
                // Inside of strings, completely remove newlines and accompanying preceding backslashes.
                // Also, "jump over" strings so that any comments they might contain are not removed.
                '"', '\'' -> {
                    while (++i < text.length)
                        when (text[i]) {
                            c -> if (text[i - 1] != '\\') break
                            '\r', '\n', '\u000C' -> {
                                out.append(text, s, i - if (text[i - 1] == '\\') 1 else 0)
                                s = i + 1
                            }
                        }
                }
                // Remove comments.
                '/' -> if (i + 1 < text.length && text[i + 1] == '*') {
                    out.append(text, s, i)
                    while (++i < text.length && !(text[i] == '/' && text[i - 1] == '*'));
                    if (i == text.length)
                        return out.toString()
                    s = i + 1
                }
            }
        out.append(text, s, i)
        return out.toString()
    }

    /**
     * @param start Index of opening quote
     * @return Index of closing quote
     */
    private fun findEndOfString(text: String, start: Int, quote: Char): Int {
        var i = start
        while (++i < text.length && !(text[i] == quote && text[i - 1] != '\\'));
        return i
    }

    /**
     * @param start Index of opening '@'
     * @return Index of closing ';' or '}'
     */
    private fun findEndOfAtRule(text: String, start: Int): Int {
        var i = start
        while (++i < text.length)
            when (val c = text[i]) {
                ';' -> return i
                '{' -> return findEndOfNestedBlocks(text, i)
                '"', '\'' -> i = findEndOfString(text, i, c)
            }
        return i
    }

    /**
     * @param start Index of opening '{'
     * @return Index of closing '}'
     */
    private fun findEndOfNestedBlocks(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (depth > 0 && ++i < text.length)
            when (val c = text[i]) {
                '{' -> depth++
                '}' -> depth--
                '"', '\'' -> i = findEndOfString(text, i, c)
            }
        return i
    }

    private class ParsingResult<T>(val result: T, val end: Int)
    private enum class CharKind { ID_CLS, ATTR, CHAIN, SEP, END }

    /**
     * @param start Index of opening selector char
     * @return Index of closing '{'
     */
    private fun parseSelectors(text: String, start: Int): ParsingResult<List<Selector>> {
        val sels = mutableListOf<Selector>()

        var simples = mutableListOf<SimpleSelector>()
        var combs = mutableListOf<Combinator>()

        var type: String? = null
        var id: String? = null
        var classes = mutableListOf<String>()
        var pseudos = mutableListOf<String>()
        var attrs = mutableListOf<AttributeCondition>()
        var comb: Combinator? = null

        var s = start
        var i = start - 1
        while (++i < text.length) {
            val kind = when (text[i]) {
                '#', '.', ':' -> CharKind.ID_CLS
                '[' -> CharKind.ATTR
                ' ', '>', '+' -> CharKind.CHAIN
                ',' -> CharKind.SEP
                '{' -> CharKind.END
                else -> continue
            }
            when (text[s]) {
                '#' -> if (s + 1 < i) id = text.substring(s + 1, i)
                '.' -> if (s + 1 < i) classes += text.substring(s + 1, i)
                ':' -> if (s + 1 < i) pseudos += text.substring(s + 1, i)
                '[' -> {}
                else -> if (s < i) type = text.substring(s, i)
            }
            if (kind == CharKind.ID_CLS)
                s = i
            else if (kind == CharKind.ATTR) {
                s = i
                val attrResult = parseAttributeSelector(text, i)
                attrResult.result?.let(attrs::add)
                i = attrResult.end
            } else {
                s = i + 1
                if (type != null || id != null || classes.isNotEmpty() || pseudos.isNotEmpty() || attrs.isNotEmpty()) {
                    simples += SimpleSelector(type ?: "*", id, classes, pseudos, attrs)
                    if (comb != null && simples.size > 1)
                        combs += comb
                    type = null
                    id = null
                    classes = mutableListOf()
                    pseudos = mutableListOf()
                    attrs = mutableListOf()
                    comb = null
                }
                if (kind == CharKind.CHAIN)
                    when (text[i]) {
                        ' ' -> if (comb == null) comb = Combinator.DESCENDANT
                        '>' -> comb = Combinator.CHILD
                        '+' -> comb = Combinator.ADJACENT
                    }
                else {
                    if (simples.isNotEmpty()) {
                        sels += Selector(simples, combs)
                        simples = mutableListOf()
                        combs = mutableListOf()
                    }
                    if (kind == CharKind.END)
                        break
                }
            }
        }
        return ParsingResult(sels, i)
    }

    /**
     * @param start Index of opening '['
     * @return Index of closing ']'
     */
    private fun parseAttributeSelector(text: String, start: Int): ParsingResult<AttributeCondition?> {
        var attr: String? = null
        var match: AttributeMatcher? = null
        var value: String? = null
        var i = start
        var eq = -1
        while (++i < text.length)
            when (val c = text[i]) {
                '=' -> {
                    match = when (text[i - 1]) {
                        '~' -> AttributeMatcher.INCLUDES
                        '|' -> AttributeMatcher.DASH_MATCH
                        else -> AttributeMatcher.EQUALS
                    }
                    attr = text.substring(start + 1, i - if (match == AttributeMatcher.EQUALS) 0 else 1).trim(' ')
                    eq = i
                }

                '"', '\'' -> {
                    val valueBuilder = StringBuilder()
                    var s = i + 1
                    while (++i < text.length)
                        if (text[i] == c)
                            if (text[i - 1] == '\\') {
                                valueBuilder.append(text, s, i - 1)
                                s = i
                            } else {
                                valueBuilder.append(text, s, i)
                                break
                            }
                    value = valueBuilder.toString()
                }

                ']' -> break
            }
        if (attr == null)
            attr = text.substring(start + 1, i).trim(' ')
        if (value == null && eq != -1)
            value = text.substring(eq + 1, i).trim(' ')
        return when {
            attr.isEmpty() -> ParsingResult(null, i)
            value.isNullOrEmpty() -> ParsingResult(AttributeCondition(attr, null, null), i)
            else -> ParsingResult(AttributeCondition(attr, match, value), i)
        }
    }

    /**
     * @param start Index of opening '{'
     * @return Index of closing '}'
     */
    private fun parseDeclarations(text: String, start: Int): ParsingResult<List<Declaration>> {
        val decls = mutableListOf<Declaration>()
        var prop: String? = null
        var s = start + 1
        var i = start
        while (true)
            when (val c = if (++i >= text.length) '}' else text[i]) {
                ':' ->
                    if (prop == null) {
                        prop = text.substring(s, i).trim(' ')
                        s = i + 1
                    }

                ';', '}' -> {
                    if (!prop.isNullOrEmpty()) {
                        var value = text.substring(s, i).trim(' ')
                        var imp = false
                        if (value.endsWith("important"))
                            for (i2 in value.length - 10 downTo 0)
                                when (value[i2]) {
                                    ' ' -> continue
                                    '!' -> {
                                        value = value.substring(0, i2).trimEnd(' ')
                                        imp = true
                                        break
                                    }

                                    else -> break
                                }
                        if (value.isNotEmpty())
                            decls += Declaration(prop, value, imp)
                    }
                    prop = null
                    if (c == ';') s = i + 1 else break
                }

                '"', '\'' -> i = findEndOfString(text, i, c)
            }
        return ParsingResult(decls, i)
    }

}
