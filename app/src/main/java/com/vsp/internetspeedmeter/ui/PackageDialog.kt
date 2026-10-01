package com.vsp.internetspeedmeter.ui

import android.content.Context
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.DialogPackageBinding
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.DataPackage
import com.vsp.internetspeedmeter.util.PackageStore

/** Sets, edits or removes the "بستهٔ اینترنت" (binary units, as the rest of the app). */
object PackageDialog {

    private const val MIB = 1_048_576.0
    private const val GIB = 1_073_741_824.0

    fun show(context: Context, onChanged: () -> Unit) {
        val b = DialogPackageBinding.inflate(LayoutInflater.from(context))
        val today = AppCalendar.today(context)
        val current = PackageStore.load(context)

        if (current != null) {
            val inGb = current.volume >= GIB
            val amount = current.volume / if (inGb) GIB else MIB
            b.etVolume.setText(if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(java.util.Locale.US, amount))
            b.toggleUnit.check(if (inGb) R.id.btn_unit_gb else R.id.btn_unit_mb)
            b.etDays.setText(current.days.toString())
            b.etStartedAgo.setText((today - current.startDay).coerceAtLeast(0).toString())
        } else {
            b.etDays.setText("30")
            b.etStartedAgo.setText("0")
        }

        fun preview() {
            val ago = PackageStore.parseNumber(b.etStartedAgo.text)?.toInt()?.coerceAtLeast(0) ?: 0
            b.tvStartPreview.text = context.getString(
                R.string.package_start_fmt, AppCalendar.dayLabel(context, today - ago))
        }
        preview()
        b.etStartedAgo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = preview()
        })

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.package_title)
            .setView(b.root)
            .setPositiveButton(R.string.package_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (current != null) {
            builder.setNeutralButton(R.string.package_delete) { _, _ ->
                PackageStore.clear(context)
                onChanged()
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val amount = PackageStore.parseNumber(b.etVolume.text)
                val days = PackageStore.parseNumber(b.etDays.text)?.toInt()
                val ago = PackageStore.parseNumber(b.etStartedAgo.text)?.toInt() ?: 0
                if (amount == null || amount <= 0.0 || days == null || days !in 1..3650 || ago < 0) {
                    Toast.makeText(context, R.string.package_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val unit = if (b.toggleUnit.checkedButtonId == R.id.btn_unit_mb) MIB else GIB
                PackageStore.save(context, DataPackage((amount * unit).toLong(), today - ago, days))
                onChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
