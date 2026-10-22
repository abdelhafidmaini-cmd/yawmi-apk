package com.yawmi.personalassistant

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.view.Gravity

/**
 * شاشة مبرر استخدام الصلاحيات الصحية (Permissions Rationale Activity)
 * مطلوبة رسميًا من Android Health Connect لعرض سبب قراءة البيانات الصحية للمستخدم داخل إعدادات النظام.
 */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val titleView = TextView(this).apply {
            text = "مبرر استخدام البيانات الصحية في تطبيق يومي | YAWMI"
            textSize = 20f
        }

        val bodyView = TextView(this).apply {
            text = """
                يطلب تطبيق «يومي | YAWMI» صلاحيات القراءة فقط (Read-Only) من خدمة Android Health Connect لعرض ملخص نشاطك اليومي ومؤشراتك الحيوية داخل لوحة الصحة المحلية على هاتفك:
                
                • الخطوات والمسافة والسعرات (Steps, Distance, Active & Total Calories): لعرض تقدم نشاطك البدني اليومي دون تكرار العد بين الهاتف والسوار.
                • معدل ضربات القلب وضربات القلب أثناء الراحة (Heart Rate & Resting Heart Rate): لعرض آخر قراءة وتوقيتها وسجل النبض اليومي.
                • النوم ومراحله (Sleep Sessions & Stages): لحساب ساعات النوم الفعلية ومراحل النوم دون مضاعفة الفترات المتداخلة.
                • الوزن والتمارين والأكسجين ومعدل التنفس (Weight, Exercise, SpO2, Respiratory Rate): لعرض سجل القياسات والتمارين المسجلة من تطبيقاتك الصحية.
                
                الخصوصية والأمان:
                تُحفظ جميع البيانات محليًا على جهازك فقط داخل قاعدة بيانات التطبيق، ولا يتم بيعها أو رفعها إلى أي خادم خارجي. يمكنك إلغاء الصلاحيات أو حذف البيانات في أي وقت.
            """.trimIndent()
            textSize = 15f
            setPadding(0, 32, 0, 48)
        }

        val closeBtn = Button(this).apply {
            text = "فهمت وإغلاق"
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }

        layout.addView(titleView)
        layout.addView(bodyView)
        layout.addView(closeBtn)
        scroll.addView(layout)
        setContentView(scroll)
    }
}
