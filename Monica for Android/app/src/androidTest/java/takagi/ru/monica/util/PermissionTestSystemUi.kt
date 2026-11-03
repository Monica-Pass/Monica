package takagi.ru.monica.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Read actual system windows, including dialogs on another interactive window. */
class PermissionTestSystemUi : AutoCloseable {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val originalFlags = automation.serviceInfo.flags
    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }
    private fun roots() = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
    fun hasPackage(packageName:String) = roots().any { it.packageName?.toString()==packageName }
    fun hasDocumentPicker() = roots().any { it.packageName?.toString()?.endsWith(".documentsui")==true }
    fun find(label:String):AccessibilityNodeInfo? {
        fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.text?.toString()==label || node.contentDescription?.toString()?.startsWith(label,ignoreCase=true)==true ||
                node.viewIdResourceName?.endsWith(":id/$label")==true)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let { visit(it) }?.let{return it}
            return null
        }
        return roots().firstNotNullOfOrNull { visit(it) }
    }
    fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15_000
        while(!condition()) {
            check(SystemClock.elapsedRealtime()<deadline) { "$message; windows=${roots().map{it.packageName}}" }
            SystemClock.sleep(100)
        }
    }
    fun click(label:String) {
        await("Missing system control $label") { find(label)!=null }
        automation.waitForIdle(200,5_000)
        val target=checkNotNull(find(label))
        var node:AccessibilityNodeInfo?=target
        while(node!=null && !node.isClickable)node=node.parent
        if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
        tap(label)
    }
    fun tap(label:String) {
        await("Missing system tap target $label") { find(label)!=null }
        val target=checkNotNull(find(label))
        // DocumentsUI rows can expose a non-clickable label without an actionable parent.
        // Tap that observed label in the actual system window; never grant permissions via shell.
        val bounds=Rect();target.getBoundsInScreen(bounds)
        println("System tap: $label; class=${target.className}; id=${target.viewIdResourceName}; bounds=$bounds")
        check(target.isVisibleToUser && !bounds.isEmpty) { "Invisible system control $label" }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}"))
            .use { it.readBytes() }
    }
    override fun close() {
        automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
    }
}
