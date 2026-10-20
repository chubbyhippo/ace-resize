package io.github.chubbyhippo.aceresize

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

class AceResizeAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val editor = CommonDataKeys.EDITOR.getData(event.dataContext) ?: return
        ApplicationManager.getApplication().getService(AceResizeLifecycle::class.java)
        AceResize.start(editor)
    }
}

class AceResizeTypedHandler(
    private val original: TypedActionHandler,
) : TypedActionHandler {
    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        if (!AceResize.handleTyped(charTyped)) {
            original.execute(editor, charTyped, dataContext)
        }
    }
}

@Service(Service.Level.APP)
class AceResizeLifecycle : Disposable {
    init {
        IdeEventQueue.getInstance().addDispatcher(AceResize.dispatcher, this)
    }

    override fun dispose() {
        AceResize.cancel()
    }
}

object AceResize {
    const val STEP = 0.05f
    const val MIN_PROPORTION = 0.05f
    private const val LABEL_KEYS = "asdfghjkl"

    enum class Dir { LEFT, RIGHT, DOWN, UP }

    enum class Axis { HORIZONTAL, VERTICAL, BOTH }

    private enum class Phase { PICK, HOLD }

    private class Target(
        val axis: Axis,
        val layer: JLayeredPane,
        val center: () -> Point?,
        val resize: (Dir) -> Unit,
    )

    private class Session(
        val targets: List<Target>,
    ) {
        var phase = Phase.PICK
        var prefix = ""
        var picked: Target? = null
        val canvases = mutableListOf<JComponent>()
    }

    private var session: Session? = null

    internal val dispatcher =
        object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean = this@AceResize.dispatch(e)
        }

    fun nudge(
        vertical: Boolean,
        dir: Dir,
        proportion: Float,
        step: Float = STEP,
    ): Float? {
        val delta =
            when {
                vertical && dir == Dir.UP -> -step
                vertical && dir == Dir.DOWN -> step
                !vertical && dir == Dir.LEFT -> -step
                !vertical && dir == Dir.RIGHT -> step
                else -> return null
            }
        return (proportion + delta).coerceIn(MIN_PROPORTION, 1f - MIN_PROPORTION)
    }

    fun start(editor: Editor) {
        val frame = SwingUtilities.getWindowAncestor(editor.component) ?: return
        val layer = (frame as? RootPaneContainer)?.rootPane?.layeredPane ?: return
        val targets = ordered(splitterTargets(frame, layer) + toolWindowTargets(editor, frame, layer))
        cancel()
        if (targets.isEmpty()) return
        session = Session(targets).also(::paintLabels)
    }

    fun cancel() {
        session?.canvases?.forEach(::detach)
        session = null
    }

    fun handleTyped(char: Char): Boolean {
        val active = session ?: return false
        if (active.phase == Phase.HOLD) {
            val direction = vimDir(char)
            if (direction == null) cancel() else resize(active, direction)
        } else if (char.isLetter()) {
            pick(active, char)
        }
        return true
    }

    private fun dispatch(event: AWTEvent): Boolean {
        if (event !is KeyEvent) return false
        val active = session ?: return false
        if (event.id == KeyEvent.KEY_TYPED && event.modifiersEx == 0) {
            event.consume()
            return true
        }
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.keyCode == KeyEvent.VK_ESCAPE && event.modifiersEx == 0) {
            event.consume()
            cancel()
            return true
        }
        val direction = arrowDir(event)
        if (active.phase == Phase.HOLD && direction != null) {
            event.consume()
            resize(active, direction)
            return true
        }
        val char = keyChar(event)
        if (active.phase == Phase.PICK && char != null) {
            event.consume()
            pick(active, char)
            return true
        }
        if (active.phase == Phase.HOLD && event.modifiersEx == 0) {
            event.consume()
            val vimDirection = char?.let(::vimDir)
            if (vimDirection != null) resize(active, vimDirection) else cancel()
            return true
        }
        return false
    }

    private fun pick(
        active: Session,
        char: Char,
    ) {
        val prefix = active.prefix + char.lowercaseChar()
        val matches = active.targets.indices.filter { labelFor(it).startsWith(prefix) }
        if (matches.isEmpty()) return
        val picked = matches.singleOrNull { labelFor(it) == prefix }
        if (picked != null) {
            active.phase = Phase.HOLD
            active.picked = active.targets[picked]
            active.prefix = ""
            paintHold(active)
        } else {
            active.prefix = prefix
            paintLabels(active)
        }
    }

    private fun resize(
        active: Session,
        dir: Dir,
    ) {
        val target = active.picked ?: return
        if (accepts(target.axis, dir)) {
            ApplicationManager.getApplication().invokeLater {
                if (session === active) target.resize(dir)
            }
            active.canvases.forEach { it.repaint() }
        }
    }

    private fun ordered(targets: List<Target>): List<Target> =
        targets.sortedBy { target -> target.center()?.let { it.x * 100_000 + it.y } ?: Int.MAX_VALUE }

    private fun splitterTargets(
        frame: Window,
        layer: JLayeredPane,
    ): List<Target> =
        visibleComponents(frame)
            .filterIsInstance<Splitter>()
            .filter { it.firstComponent != null && it.secondComponent != null && it.isShowing }
            .mapNotNull { splitter ->
                val first = splitter.firstComponent ?: return@mapNotNull null
                val axis = if (splitter.isVertical) Axis.VERTICAL else Axis.HORIZONTAL
                target(layer, axis, {
                    val spot =
                        if (splitter.isVertical) Rectangle(0, first.y + first.height, splitter.width, 1)
                        else Rectangle(first.x + first.width, 0, 1, splitter.height)
                    val rect = SwingUtilities.convertRectangle(splitter, spot, layer)
                    Point(rect.x + rect.width / 2, rect.y + rect.height / 2)
                }) { dir ->
                    nudge(splitter.isVertical, dir, splitter.proportion)?.let { splitter.proportion = it }
                }
            }

    private fun toolWindowTargets(
        editor: Editor,
        frame: Window,
        layer: JLayeredPane,
    ): List<Target> {
        val project = editor.project ?: return emptyList()
        val manager = ToolWindowManager.getInstance(project)
        val area = FileEditorManagerEx.getInstanceEx(project).splitters
        return manager.toolWindowIds.mapNotNull { id ->
            val toolWindow = manager.getToolWindow(id)?.takeIf { it.isVisible } ?: return@mapNotNull null
            val component = toolWindow.component
            if (!component.isShowing || SwingUtilities.getWindowAncestor(component) !== frame) return@mapNotNull null
            val axis =
                when (toolWindow.anchor) {
                    ToolWindowAnchor.LEFT, ToolWindowAnchor.RIGHT -> Axis.HORIZONTAL
                    ToolWindowAnchor.TOP, ToolWindowAnchor.BOTTOM -> Axis.VERTICAL
                    else -> Axis.BOTH
                }
            target(layer, axis, { toolWindowBoundary(area, component, toolWindow.anchor, layer) }) { dir ->
                val action = ActionManagerEx.getInstanceEx().getAction(toolWindowAction(dir)) ?: return@target
                ActionManagerEx.getInstanceEx().tryToExecute(action, null, component, "AceResize", true)
            }
        }
    }

    private fun target(
        layer: JLayeredPane,
        axis: Axis,
        center: () -> Point?,
        resize: (Dir) -> Unit,
    ): Target? = center()?.let { Target(axis, layer, center, resize) }

    private fun toolWindowBoundary(
        area: JComponent,
        toolWindow: Component,
        anchor: ToolWindowAnchor,
        layer: JLayeredPane,
    ): Point? {
        if (!area.isShowing || !toolWindow.isShowing) return null
        val areaRect = SwingUtilities.convertRectangle(area, Rectangle(0, 0, area.width, area.height), layer)
        val toolWindowRect = SwingUtilities.convertRectangle(toolWindow, Rectangle(0, 0, toolWindow.width, toolWindow.height), layer)
        return when (anchor) {
            ToolWindowAnchor.LEFT -> Point(areaRect.x, toolWindowRect.y + toolWindowRect.height / 2)
            ToolWindowAnchor.RIGHT -> Point(areaRect.x + areaRect.width, toolWindowRect.y + toolWindowRect.height / 2)
            ToolWindowAnchor.TOP -> Point(toolWindowRect.x + toolWindowRect.width / 2, areaRect.y)
            ToolWindowAnchor.BOTTOM -> Point(toolWindowRect.x + toolWindowRect.width / 2, areaRect.y + areaRect.height)
            else -> Point(toolWindowRect.x + toolWindowRect.width / 2, toolWindowRect.y + toolWindowRect.height / 2)
        }
    }

    private fun paintLabels(active: Session) {
        clearCanvases(active)
        active.targets.forEachIndexed { index, target ->
            val label = labelFor(index)
            if (label.startsWith(active.prefix)) {
                active.canvases += layerCanvas(target.layer, ResizeBadges(listOf(target.center to label.removePrefix(active.prefix))))
            }
        }
    }

    private fun paintHold(active: Session) {
        clearCanvases(active)
        val target = active.picked ?: return
        active.canvases += layerCanvas(target.layer, ResizeBadges(listOf(target.center to holdLabel(target.axis))))
    }

    private fun clearCanvases(active: Session) {
        active.canvases.forEach(::detach)
        active.canvases.clear()
    }

    private fun labelFor(index: Int): String {
        var remaining = index
        val label = StringBuilder()
        do {
            label.append(LABEL_KEYS[remaining % LABEL_KEYS.length])
            remaining = remaining / LABEL_KEYS.length - 1
        } while (remaining >= 0)
        return label.reverse().toString()
    }

    private fun holdLabel(axis: Axis): String =
        when (axis) {
            Axis.HORIZONTAL -> "← →"
            Axis.VERTICAL -> "↓ ↑"
            Axis.BOTH -> "←→↓↑"
        }

    private fun toolWindowAction(dir: Dir): String =
        when (dir) {
            Dir.LEFT -> "ResizeToolWindowLeft"
            Dir.RIGHT -> "ResizeToolWindowRight"
            Dir.DOWN -> "ResizeToolWindowDown"
            Dir.UP -> "ResizeToolWindowUp"
        }

    private fun accepts(
        axis: Axis,
        dir: Dir,
    ): Boolean =
        when (axis) {
            Axis.HORIZONTAL -> dir == Dir.LEFT || dir == Dir.RIGHT
            Axis.VERTICAL -> dir == Dir.UP || dir == Dir.DOWN
            Axis.BOTH -> true
        }

    private fun arrowDir(event: KeyEvent): Dir? {
        if (event.modifiersEx != 0) return null
        return when (event.keyCode) {
            KeyEvent.VK_LEFT -> Dir.LEFT
            KeyEvent.VK_RIGHT -> Dir.RIGHT
            KeyEvent.VK_DOWN -> Dir.DOWN
            KeyEvent.VK_UP -> Dir.UP
            else -> null
        }
    }

    private fun vimDir(char: Char): Dir? =
        when (char) {
            'h' -> Dir.LEFT
            'l' -> Dir.RIGHT
            'j' -> Dir.DOWN
            'k' -> Dir.UP
            else -> null
        }

    private fun keyChar(event: KeyEvent): Char? {
        if (event.modifiersEx != 0 || event.keyCode !in KeyEvent.VK_A..KeyEvent.VK_Z) return null
        return ('a'.code + event.keyCode - KeyEvent.VK_A).toChar()
    }

    private fun visibleComponents(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        fun visit(component: Component) {
            if (!component.isShowing) return
            result += component
            if (component is java.awt.Container) component.components.forEach(::visit)
        }
        visit(root)
        return result
    }

    private fun layerCanvas(
        layer: JLayeredPane,
        canvas: JComponent,
    ): JComponent {
        canvas.isOpaque = false
        canvas.bounds = Rectangle(0, 0, layer.width, layer.height)
        layer.setLayer(canvas, JLayeredPane.DRAG_LAYER)
        layer.add(canvas)
        layer.repaint()
        return canvas
    }

    private fun detach(canvas: JComponent) {
        canvas.parent?.let { parent ->
            parent.remove(canvas)
            parent.repaint()
        }
    }

    private class ResizeBadges(
        private val entries: List<Pair<() -> Point?, String>>,
    ) : JComponent() {
        override fun contains(
            x: Int,
            y: Int,
        ): Boolean = false

        override fun paintComponent(graphics: Graphics) {
            val g = graphics as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = g.font.deriveFont(Font.BOLD)
            entries.forEach { (center, label) ->
                val point = center() ?: return@forEach
                val padding = 4
                val glyphBounds = g.font.createGlyphVector(g.fontRenderContext, label).visualBounds
                val width = kotlin.math.ceil(glyphBounds.width).toInt() + padding * 2
                val height = kotlin.math.ceil(glyphBounds.height).toInt() + padding * 2
                val x = point.x - width / 2
                val y = point.y - height / 2
                val baselineX = x + padding - glyphBounds.x
                val baselineY = y + padding - glyphBounds.y
                g.color = java.awt.Color(0x2E, 0x34, 0x40)
                g.fillRect(x, y, width, height)
                g.color = java.awt.Color.WHITE
                g.drawString(label, baselineX.toFloat(), baselineY.toFloat())
            }
        }
    }
}
