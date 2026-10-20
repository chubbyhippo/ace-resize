package io.github.chubbyhippo.aceresize

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.ui.Splitter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Method
import javax.swing.JPanel

class AceResizeSpec : BasePlatformTestCase() {
    private fun pressed(
        keyCode: Int,
        modifiers: Int = 0,
    ) = KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    private fun <T> privateCall(
        name: String,
        vararg arguments: Any,
    ): T {
        val method =
            AceResize::class.java.getDeclaredMethod(
                name,
                *arguments
                    .map { argument ->
                        when (argument.javaClass) {
                            java.lang.Character::class.java -> Char::class.javaPrimitiveType
                            java.lang.Integer::class.java -> Int::class.javaPrimitiveType
                            else -> argument.javaClass
                        }
                    }.toTypedArray(),
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AceResize, *arguments) as T
    }

    private fun privateCall(
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg arguments: Any,
    ): Any? {
        val method: Method = AceResize::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(AceResize, *arguments)
    }

    fun `test given horizontal splitter then nudge moves left and right only`() {
        assertEquals(0.45f, AceResize.nudge(false, AceResize.Dir.LEFT, 0.5f, 0.05f))
        assertEquals(0.55f, AceResize.nudge(false, AceResize.Dir.RIGHT, 0.5f, 0.05f))
        assertNull(AceResize.nudge(false, AceResize.Dir.UP, 0.5f))
        assertNull(AceResize.nudge(false, AceResize.Dir.DOWN, 0.5f))
    }

    fun `test given vertical splitter then nudge moves up and down only`() {
        assertEquals(0.45f, AceResize.nudge(true, AceResize.Dir.UP, 0.5f, 0.05f))
        assertEquals(0.55f, AceResize.nudge(true, AceResize.Dir.DOWN, 0.5f, 0.05f))
        assertNull(AceResize.nudge(true, AceResize.Dir.LEFT, 0.5f))
        assertNull(AceResize.nudge(true, AceResize.Dir.RIGHT, 0.5f))
    }

    fun `test given boundary proportions then nudge clamps to the permitted band`() {
        assertEquals(AceResize.MIN_PROPORTION, AceResize.nudge(false, AceResize.Dir.LEFT, 0.06f))
        assertEquals(1f - AceResize.MIN_PROPORTION, AceResize.nudge(true, AceResize.Dir.DOWN, 0.94f))
    }

    fun `test given target positions then labels follow ace subdivision`() {
        assertEquals("a", privateCall<String>("labelFor", 0))
        assertEquals("l", privateCall<String>("labelFor", 8))
        assertEquals("aa", privateCall<String>("labelFor", 9))
        assertEquals("as", privateCall<String>("labelFor", 10))
        assertEquals("al", privateCall<String>("labelFor", 17))
        assertEquals("aaa", privateCall<String>("labelFor", 90))
    }

    fun `test given axes then labels and accepted directions match`() {
        assertEquals("← →", privateCall<String>("holdLabel", AceResize.Axis.HORIZONTAL))
        assertEquals("↓ ↑", privateCall<String>("holdLabel", AceResize.Axis.VERTICAL))
        assertEquals("←→↓↑", privateCall<String>("holdLabel", AceResize.Axis.BOTH))
        assertTrue(privateCall<Boolean>("accepts", AceResize.Axis.HORIZONTAL, AceResize.Dir.LEFT))
        assertFalse(privateCall<Boolean>("accepts", AceResize.Axis.HORIZONTAL, AceResize.Dir.UP))
        assertTrue(privateCall<Boolean>("accepts", AceResize.Axis.VERTICAL, AceResize.Dir.DOWN))
        assertFalse(privateCall<Boolean>("accepts", AceResize.Axis.VERTICAL, AceResize.Dir.RIGHT))
        assertTrue(privateCall<Boolean>("accepts", AceResize.Axis.BOTH, AceResize.Dir.UP))
    }

    fun `test given resize directions then tool window action ids match`() {
        assertEquals("ResizeToolWindowLeft", privateCall<String>("toolWindowAction", AceResize.Dir.LEFT))
        assertEquals("ResizeToolWindowRight", privateCall<String>("toolWindowAction", AceResize.Dir.RIGHT))
        assertEquals("ResizeToolWindowDown", privateCall<String>("toolWindowAction", AceResize.Dir.DOWN))
        assertEquals("ResizeToolWindowUp", privateCall<String>("toolWindowAction", AceResize.Dir.UP))
    }

    fun `test given hjkl then resize directions map and unrelated keys do not`() {
        assertEquals(AceResize.Dir.LEFT, privateCall<AceResize.Dir?>("vimDir", 'h'))
        assertEquals(AceResize.Dir.RIGHT, privateCall<AceResize.Dir?>("vimDir", 'l'))
        assertEquals(AceResize.Dir.DOWN, privateCall<AceResize.Dir?>("vimDir", 'j'))
        assertEquals(AceResize.Dir.UP, privateCall<AceResize.Dir?>("vimDir", 'k'))
        assertNull(privateCall<AceResize.Dir?>("vimDir", 'x'))
    }

    fun `test given unmodified arrow keys then directions map and other keys do not`() {
        assertEquals(AceResize.Dir.LEFT, privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_LEFT)))
        assertEquals(AceResize.Dir.RIGHT, privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_RIGHT)))
        assertEquals(AceResize.Dir.DOWN, privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_DOWN)))
        assertEquals(AceResize.Dir.UP, privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_UP)))
        assertNull(privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_A)))
        assertNull(privateCall<AceResize.Dir?>("arrowDir", pressed(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK)))
    }

    fun `test given letter key presses then keyChar normalizes unmodified letters only`() {
        assertEquals('a', privateCall<Char?>("keyChar", pressed(KeyEvent.VK_A)))
        assertEquals('z', privateCall<Char?>("keyChar", pressed(KeyEvent.VK_Z)))
        assertNull(privateCall<Char?>("keyChar", pressed(KeyEvent.VK_LEFT)))
        assertNull(privateCall<Char?>("keyChar", pressed(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)))
    }

    fun `test given no active resize then typed characters are delegated`() {
        AceResize.cancel()
        assertFalse(AceResize.handleTyped('a'))
    }

    fun `test given no editor then ace resize action is a no op`() {
        AceResizeAction().actionPerformed(
            com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "AceResizeSpec",
                null,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            ),
        )
        assertNull(ApplicationManager.getApplication().getServiceIfCreated(AceResizeLifecycle::class.java))
    }

    fun `test given no active resize then typed handler delegates to its original handler`() {
        var delegated = false
        val handler =
            AceResizeTypedHandler(
                object : TypedActionHandler {
                    override fun execute(
                        editor: Editor,
                        charTyped: Char,
                        dataContext: com.intellij.openapi.actionSystem.DataContext,
                    ) {
                        delegated = true
                    }
                },
            )

        myFixture.configureByText("typed-handler.txt", "text")
        AceResize.cancel()
        handler.execute(myFixture.editor, 'a', com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT)

        assertTrue(delegated)
    }

    fun `test given splitter orientation then nudge accepts the corresponding direction`() {
        val horizontal = Splitter(false, 0.5f)
        val vertical = Splitter(true, 0.5f)

        assertNotNull(AceResize.nudge(horizontal.isVertical, AceResize.Dir.RIGHT, horizontal.proportion))
        assertNotNull(AceResize.nudge(vertical.isVertical, AceResize.Dir.DOWN, vertical.proportion))
    }
}
