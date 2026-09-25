package com.moonsonglabs.daml.sandbox

import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList
import java.awt.Component

/** Native selection, focus, keyboard navigation and IDE theme behavior. Deletion lives in profile settings. */
internal class ProfileComboBox(
    model: DefaultComboBoxModel<SandboxProfile>
) : JComboBox<SandboxProfile>(model) {
    init {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? SandboxProfile)?.name ?: "No profiles", index, selected, focus)
        }
        toolTipText = "Select a network profile"
        getAccessibleContext().accessibleName = "Network profile"
    }
}
