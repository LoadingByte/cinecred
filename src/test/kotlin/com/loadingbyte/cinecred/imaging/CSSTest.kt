package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.imaging.CSS.AttributeMatcher.*
import com.loadingbyte.cinecred.imaging.CSS.Combinator.*
import com.loadingbyte.cinecred.imaging.CSS.Ruleset
import com.loadingbyte.cinecred.imaging.CSS.Selector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource


internal class CSSTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "fill: blue", "  fill  :blue  ", "fill: blue;", ";;fill: blue;;", "fill: blue } stroke: red",
            "fill: blue; stroke:", "fill: blue; : red", ":::;fill:blue;stroke:;:red:",
            "/* Hi */ fill: /* Hi */ blue /* Hi */", "fill\n:\nblue"
        ]
    )
    fun `parse one inline declaration`(text: String) {
        assertEquals(l(decl("fill", "blue")), CSS.parseDeclarations(text))
    }

    @ParameterizedTest
    @ValueSource(strings = [":blue", "blue:", "blue:red", ": :blue:red ::"])
    fun `parse one inline declaration with multiple colons`(value: String) {
        assertEquals(l(decl("fill", value)), CSS.parseDeclarations("fill:$value"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["border: 1px solid 'a\\';}b' black", "  ; border: 1px\nsolid '\\\na\\'\r;}b' black  ;"])
    fun `parse one inline declaration with a complex value`(text: String) {
        assertEquals(l(decl("border", "1px solid 'a\\';}b' black")), CSS.parseDeclarations(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["fill: blue !important", "fill: blue  !   important", "fill: blue!important"])
    fun `parse one inline declaration with importance`(text: String) {
        assertEquals(l(decl("fill", "blue", important = true)), CSS.parseDeclarations(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "fill: blue; stroke: red", "  fill : blue;   stroke:red ", ";; fill:blue;stroke:red ;;",
            ";;:a;:b:;fill:blue;::;stroke:red;;", "fill:\nblue\n;stroke\n:red", "fill: blue /* Hi */; stroke: red"
        ]
    )
    fun `parse two inline declarations`(text: String) {
        assertEquals(
            l(decl("fill", "blue"), decl("stroke", "red")),
            CSS.parseDeclarations(text)
        )
    }

    @ParameterizedTest
    @MethodSource("selectors")
    fun `parse selectors in ruleset`(text: String, expectedSelectors: List<Selector>) {
        assertEquals(
            l(Ruleset(expectedSelectors, l(decl("fill", "blue")))),
            CSS.parseStylesheet("$text { fill: blue }")
        )
    }

    fun selectors() = l(
        arguments("", l<Selector>()),
        arguments("*", l(Selector(l(simp()), l()))),
        arguments("rect", l(Selector(l(simp("rect")), l()))),
        arguments("#myElem", l(Selector(l(simp(id = "myElem")), l()))),
        arguments(".my-class", l(Selector(l(simp(classes = l("my-class"))), l()))),
        arguments(":focus", l(Selector(l(simp(pseudos = l("focus"))), l()))),
        arguments("*.my-class", l(Selector(l(simp(classes = l("my-class"))), l()))),
        arguments(
            "g.cl1:p1#id1.cl2:p2",
            l(Selector(l(simp("g", "id1", l("cl1", "cl2"), l("p1", "p2"))), l()))
        ),
        arguments("  rect  ", l(Selector(l(simp("rect")), l()))),
        arguments(".:#myElem", l(Selector(l(simp(id = "myElem")), l()))),
        arguments("[]", l<Selector>()),
        arguments("[a]", l(Selector(l(simp(attrs = l(attr("a")))), l()))),
        arguments("[ ab  ]", l(Selector(l(simp(attrs = l(attr("ab")))), l()))),
        arguments("  [ab]  ", l(Selector(l(simp(attrs = l(attr("ab")))), l()))),
        arguments("*[ab]", l(Selector(l(simp(attrs = l(attr("ab")))), l()))),
        arguments("[=]", l<Selector>()),
        arguments("[ ~=  ]", l<Selector>()),
        arguments("[|=b]", l<Selector>()),
        arguments("[a=]", l(Selector(l(simp(attrs = l(attr("a")))), l()))),
        arguments("[a=  ]", l(Selector(l(simp(attrs = l(attr("a")))), l()))),
        arguments("[a=b]", l(Selector(l(simp(attrs = l(attr("a", EQUALS, "b")))), l()))),
        arguments("[a~=b]", l(Selector(l(simp(attrs = l(attr("a", INCLUDES, "b")))), l()))),
        arguments("[a|=b]", l(Selector(l(simp(attrs = l(attr("a", DASH_MATCH, "b")))), l()))),
        arguments("[abc=def]", l(Selector(l(simp(attrs = l(attr("abc", EQUALS, "def")))), l()))),
        arguments("[  ab   = cd  ]", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "cd")))), l()))),
        arguments("[ab='']", l(Selector(l(simp(attrs = l(attr("ab")))), l()))),
        arguments("[ab='cd']", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "cd")))), l()))),
        arguments("[ab=\"cd\"]", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "cd")))), l()))),
        arguments("[ab='c\\'d]e']", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "c'd]e")))), l()))),
        arguments("[ab=\"c\\\"d]e\"]", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "c\"d]e")))), l()))),
        arguments("[ab= 'cd'  ]", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "cd")))), l()))),
        arguments("[ab= c 'de'f]", l(Selector(l(simp(attrs = l(attr("ab", EQUALS, "de")))), l()))),
        arguments(
            "path[ a =b][c ~=de ]",
            l(Selector(l(simp("path", attrs = l(attr("a", EQUALS, "b"), attr("c", INCLUDES, "de")))), l()))
        ),
        arguments(
            "g.cl[a=b]#id",
            l(Selector(l(simp("g", id = "id", classes = l("cl"), attrs = l(attr("a", EQUALS, "b")))), l()))
        ),
        arguments("g path", l(Selector(l(simp("g"), simp("path")), l(DESCENDANT)))),
        arguments("g .cl", l(Selector(l(simp("g"), simp(classes = listOf("cl"))), l(DESCENDANT)))),
        arguments("#i g:p", l(Selector(l(simp(id = "i"), simp("g", pseudos = listOf("p"))), l(DESCENDANT)))),
        arguments("g [a=b]", l(Selector(l(simp("g"), simp(attrs = l(attr("a", EQUALS, "b")))), l(DESCENDANT)))),
        arguments("g>path", l(Selector(l(simp("g"), simp("path")), l(CHILD)))),
        arguments("g > path", l(Selector(l(simp("g"), simp("path")), l(CHILD)))),
        arguments("g > .cl", l(Selector(l(simp("g"), simp(classes = listOf("cl"))), l(CHILD)))),
        arguments("g > *[a=b]", l(Selector(l(simp("g"), simp(attrs = l(attr("a", EQUALS, "b")))), l(CHILD)))),
        arguments("g > * > g", l(Selector(l(simp("g"), simp(), simp("g")), l(CHILD, CHILD)))),
        arguments("g + path", l(Selector(l(simp("g"), simp("path")), l(ADJACENT)))),
        arguments(
            "g g > g + g",
            l(Selector(l(simp("g"), simp("g"), simp("g"), simp("g")), l(DESCENDANT, CHILD, ADJACENT)))
        ),
        arguments("g >+>+g", l(Selector(l(simp("g"), simp("g")), l(ADJACENT)))),
        arguments("path, rect", l(Selector(l(simp("path")), l()), Selector(l(simp("rect")), l()))),
        arguments("g#i, g > g", l(Selector(l(simp("g", id = "i")), l()), Selector(l(simp("g"), simp("g")), l(CHILD)))),
        arguments(
            "g.cl1#id1.cl2 /* Hallo */ > *:ps1[ ab= '\\\rc'], path[a=b][c~=d] + g\ng, rect",
            l(
                Selector(
                    l(
                        simp("g", id = "id1", classes = l("cl1", "cl2")),
                        simp(pseudos = l("ps1"), attrs = l(attr("ab", EQUALS, "c")))
                    ), l(CHILD)
                ),
                Selector(
                    l(
                        simp("path", attrs = l(attr("a", EQUALS, "b"), attr("c", INCLUDES, "d"))),
                        simp("g"),
                        simp("g")
                    ), l(ADJACENT, DESCENDANT)
                ),
                Selector(l(simp("rect")), l())
            )
        )
    )

    @Test
    fun `parse complex stylesheet`() {
        assertEquals(
            l(
                Ruleset(l(), l(decl("fill", "blue"))),
                Ruleset(
                    l(Selector(l(simp("rect", classes = l("cl1"))), l()), Selector(l(simp(id = "id1")), l())),
                    l(decl("stroke", "red", important = true), decl("content", "'hel\"lo\\' wor/* ld */'"))
                ),
                Ruleset(
                    l(Selector(l(simp("g", id = "id1", attrs = l(attr("a", EQUALS, "b")))), l())),
                    l(decl("border", "black  solid"))
                )
            ),
            CSS.parseStylesheet(
                """
/* Hallo *//* Welt */
{ fill: blue }

rect.cl1, #id1 {
  stroke: red !important;
  content: 'hel\
"lo\'
 wor
/* ld */';
  extra: /* "none" */;
}

g/* Hi */#id1[ a = /* Dings*/ b] /* Foo */ {
  /* Bums */ border /* Hi */: /* Surprise! ; */ black /* maybe } */ solid /* 1px */;
}

@import "nothing";
@media {
  please { ignore: me }
}
            """
            )
        )
    }

    private fun <E> l(vararg elements: E) = listOf(*elements)

    private fun decl(prop: String, value: String, important: Boolean = false) =
        CSS.Declaration(prop, value, important)

    private fun simp(
        type: String = "*",
        id: String? = null,
        classes: List<String> = l(),
        pseudos: List<String> = l(),
        attrs: List<CSS.AttributeCondition> = l()
    ) = CSS.SimpleSelector(type, id, classes, pseudos, attrs)

    private fun attr(attr: String, matcher: CSS.AttributeMatcher? = null, value: String? = null) =
        CSS.AttributeCondition(attr, matcher, value)

}
