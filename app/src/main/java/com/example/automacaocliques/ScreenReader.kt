package com.example.automacaocliques

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Snapshot imutavel de um no da arvore de acessibilidade, com as coordenadas em
 * pixels da tela para permitir o clique via `dispatchGesture`.
 */
data class ScreenNode(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val packageName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val enabled: Boolean,
    val depth: Int
) {
    val centerX: Float get() = bounds.exactCenterX()
    val centerY: Float get() = bounds.exactCenterY()

    /** Descricao de uma linha, usada nos logs do dump da tela. */
    fun describe(): String = buildString {
        append("  ".repeat(depth))
        append(className?.substringAfterLast('.') ?: "?")
        text?.takeIf { it.isNotBlank() }?.let { append(" text=\"").append(it).append('"') }
        contentDescription?.takeIf { it.isNotBlank() }?.let {
            append(" desc=\"").append(it).append('"')
        }
        viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        append(" bounds=").append(bounds.toShortString())
        append(" centro=(").append(centerX).append(',').append(centerY).append(')')
        if (clickable) append(" clicavel")
        if (!enabled) append(" desabilitado")
    }
}

/**
 * Criterio de busca de um no na tela. Os campos informados sao combinados com
 * "E"; [index] escolhe entre varios nos que satisfazem o criterio, permitindo
 * mais de um clique por tela em elementos parecidos.
 */
data class NodeSelector(
    /** Casa com texto, content description ou view id do no. */
    val term: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickableOnly: Boolean = false,
    val exact: Boolean = false,
    val index: Int = 0
) {
    fun matches(node: ScreenNode): Boolean {
        if (clickableOnly && !node.clickable) return false
        if (term != null &&
            !valueMatches(node.text, term) &&
            !valueMatches(node.contentDescription, term) &&
            !valueMatches(node.viewId, term)
        ) {
            return false
        }
        if (text != null && !valueMatches(node.text, text)) return false
        if (contentDescription != null &&
            !valueMatches(node.contentDescription, contentDescription)
        ) {
            return false
        }
        if (viewId != null && !valueMatches(node.viewId, viewId)) return false
        if (className != null && !valueMatches(node.className, className)) return false
        return true
    }

    private fun valueMatches(actual: String?, expected: String): Boolean {
        if (actual == null) return false
        return if (exact) {
            actual.equals(expected, ignoreCase = true)
        } else {
            actual.contains(expected, ignoreCase = true)
        }
    }

    /** Descricao curta para logs. */
    fun describe(): String = listOfNotNull(
        term?.let { "termo~$it" },
        text?.let { "text~$it" },
        contentDescription?.let { "desc~$it" },
        viewId?.let { "id~$it" },
        className?.let { "class~$it" },
        "index=$index".takeIf { index != 0 }
    ).joinToString(" ")
}

/** Leitura e busca de elementos na arvore de acessibilidade da janela ativa. */
object ScreenReader {

    /** Achata a arvore a partir de [root], ignorando nos invisiveis. */
    fun flatten(root: AccessibilityNodeInfo?): List<ScreenNode> {
        val result = mutableListOf<ScreenNode>()
        collect(root, 0, result)
        return result
    }

    /** Nos que satisfazem [selector], na ordem em que aparecem na arvore. */
    fun findAll(nodes: List<ScreenNode>, selector: NodeSelector): List<ScreenNode> =
        nodes.filter(selector::matches)

    /** No na posicao [NodeSelector.index] entre os que satisfazem [selector]. */
    fun find(nodes: List<ScreenNode>, selector: NodeSelector): ScreenNode? =
        findAll(nodes, selector).getOrNull(selector.index)

    /** Dump legivel da tela, uma linha por no. */
    fun describe(nodes: List<ScreenNode>): String =
        nodes.joinToString(separator = "\n") { it.describe() }

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<ScreenNode>
    ) {
        if (node == null) return
        if (!node.isVisibleToUser) return

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        out += ScreenNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            bounds = bounds,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            depth = depth
        )

        for (i in 0 until node.childCount) {
            collect(node.getChild(i), depth + 1, out)
        }
    }
}
