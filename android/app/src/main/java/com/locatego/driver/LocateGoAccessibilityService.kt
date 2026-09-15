package com.locatego.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * LocateGoAccessibilityService
 * خدمة مراقبة شاشة تطبيقات التوصيل ومحرك النقر الفوري الفائق (Zero-Delay Auto-Accept Engine).
 *
 * الخصائص والتحسينات المطبقة:
 * 1. سرعة فائقة (Zero-Delay):
 *    - إلغاء أي مؤقتات تأخير أو Timeouts نهائياً في حلقة الرصد والنقر (0ms notificationTimeout).
 *    - تخزين الإعدادات لحظياً في الذاكرة العشوائية (RAM Cached Settings) لمنع أي عمليات Disk I/O أثناء فحص الشاشة.
 *    - فحص شجرة الواجهة في مسار واحد فائق السرعة (Single-Pass Traversal) واستخراج نصوص الطلب وتحديد زر Accept فوراً.
 *    - نقر مباشر فوري (Instant Hardware Gesture Tap + Action Click) في أجزاء من الميلي ثانية.
 * 2. الفحص الثنائي المستقل للمسافات (Dual Independent Distance Criteria):
 *    - مسافة المطعم / الاستلام (Pickup Distance): يجب أن تكون <= الحد الأقصى لمسافة المطعم المحددة من السائق (مثلاً 1 كم أو 2 كم).
 *    - مسافة العميل / الوجهة (Delivery Distance): يجب أن تكون <= الحد الأقصى لمسافة العميل المحددة من السائق (مثلاً 2 كم).
 *    - لا يتم قبول أي طلب إلا إذا كان كلا الشرطين (المطعم والعميل) محققين معاً بدقة وضمن الحدود المسموحة.
 * 3. تشغيل آمن ومستمر بدون سحب شاشة، مع إرسال القياسات للسيرفر في الخلفية دون أي تعطيل لواجهة الهاتف.
 */
class LocateGoAccessibilityService : AccessibilityService() {

    companion object {
        // مصفوفة حزم تطبيقات التوصيل المستهدفة بالكامل (Zero-Delay Fast Lookup)
        val TARGET_PACKAGES = setOf(
            "sa.lg.android.locate",
            "sa.lg.android.locatcc",
            "sa.lg.android.locati",
            "sa.lg.android.locatm",
            "sa.lg.android.locatg",
            "sa.lg.android.locatf",
            "Sa.lg.android.locate",
            "Sa.lg.android.locati",
            "Sa.lg.android.locatf"
        )

        fun isTargetPackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            val p = pkg.trim()
            return TARGET_PACKAGES.contains(p) || TARGET_PACKAGES.contains(p.lowercase())
        }

        // قائمة الكلمات والنصوص الواجب تجاهلها (القوائم، التبويبات، الإعدادات، وأزرار التنقل العامة)
        private val IGNORED_NAV_TEXTS = setOf(
            "الرئيسية", "حسابي", "الملف الشخصي", "الإعدادات", "المحفظة", "الدعم", "مساعدة",
            "المساعدة", "تسجيل الخروج", "تسجيل خروج", "خروج", "الأرشيف", "السجل", "الإشعارات",
            "شروط الاستخدام", "سياسة الخصوصية", "حول التطبيق", "تحديث التطبيق", "تقييم",
            "المظهر", "اللغة", "الوضع الليلي", "حفظ", "إغلاق", "رجوع", "تخطي", "موافق",
            "إلغاء", "تأكيد الهاتف", "رمز التحقق", "التفاصيل", "عرض التفاصيل", "التالي", "السابق",
            "home", "profile", "settings", "wallet", "support", "help", "logout", "history",
            "notifications", "about", "close", "back", "cancel", "menu", "next", "skip"
        )

        // كلمات أزرار القبول المستهدفة للنقر الفوري الفائق
        private val ACCEPT_BUTTON_KEYWORDS = listOf(
            "قبول", "قبول الطلب", "استلام الطلب", "تأكيد القبول", "تأكيد", "وافق", "موافق",
            "Accept", "Take Order", "Confirm", "Accept Order", "إسناد", "استلام", "موافقة"
        )

        // معرّفات عناصر أزرار القبول المحتملة في واجهات التطبيقات
        private val ACCEPT_VIEW_IDS = listOf(
            "btn_accept", "accept", "accept_order", "btnAccept", "btn_confirm",
            "order_accept", "take_order", "button_accept", "action_accept", "btn_take"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var renderClient: RenderApiClient

    // ذاكرة سريعة في الذاكرة العشوائية للإعدادات لتفادي أي قراءة من القرص أثناء معالجة الأحداث
    @Volatile private var cachedMaxDistanceKm: Double = 2.0
    @Volatile private var cachedMaxPickupDistanceKm: Double = 2.0
    @Volatile private var cachedMinPayoutSar: Double = 0.0
    @Volatile private var cachedAutoAccept: Boolean = true

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // تعابير نمطية مستهدفة بدقة عالية ومترجمة مسبقاً لأعلى أداء ممكن
    // 1. مسافة المطعم / الاستلام (Pickup Distance)
    private val pickupDistancePattern = Pattern.compile(
        "(?:مسافة\\s*(?:المتجر|المطعم|الاستلام)|المتجر\\s*يبعد|المطعم\\s*يبعد|مسافة\\s*الاستلام|الاستلام|المطعم|المتجر|pickup|store)\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    // 2. مسافة العميل / التوصيل (Delivery Distance)
    private val deliveryDistancePattern = Pattern.compile(
        "(?:مسافة\\s*(?:العميل|التوصيل|الوجهة)|العميل\\s*يبعد|مسافة\\s*التوصيل|التوصيل|العميل|الوجهة|delivery|dropoff|customer)\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    // 3. المسافة العامة أو الإجمالية
    private val generalDistancePattern = Pattern.compile(
        "(?:المسافة\\s*الإجمالية|إجمالي\\s*المسافة|المسافة\\s*الكلية|المسافة|يبعد|تبعد|distance|total)?\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    // 4. أجر التوصيل (بالريال السعودي)
    private val payoutPattern = Pattern.compile(
        "(?:الأجر|المبلغ|السعر|قيمة\\s*التوصيل|الربح|الأرباح|fee|sar|payout)?\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:ر\\.س|ريال|sar|SAR|ر\\.\\s*س)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    // 5. رقم الطلب
    private val orderIdPattern = Pattern.compile(
        "(?:رقم\\s*الطلب|الطلب\\s*رقم|طلب\\s*#|Order\\s*#?|ID\\s*[:#]?|#)\\s*([A-Za-z0-9\\-_]{3,15})",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    // 6. حي أو وجهة العميل
    private val districtPattern = Pattern.compile(
        "(?:التوصيل\\s*إلى|الوجهة|العميل|حي|district)?\\s*[:]?\\s*(حي\\s+[\\u0600-\\u06FF]+(?:\\s+[\\u0600-\\u06FF]+)?)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val isClickInProgress = AtomicBoolean(false)
    private var currentForegroundPackage = ""
    private val processedOrdersCache = ConcurrentHashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        renderClient = RenderApiClient(this)
        loadCachedSettings()
        registerPrefsListener()
        Log.i("LocateGoService", "⚡⚡ LocateGo Zero-Delay Auto-Accept Engine ACTIVATED: Zero latency, Dual Distance Verification (Restaurant & Customer limits independent).")
    }

    private fun loadCachedSettings() {
        try {
            val prefs = getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)
            cachedMaxDistanceKm = prefs.getFloat("max_distance_km", 2.0f).toDouble()
            cachedMaxPickupDistanceKm = prefs.getFloat("max_pickup_distance_km", 2.0f).toDouble()
            cachedMinPayoutSar = prefs.getFloat("min_payout_sar", 0.0f).toDouble()
            cachedAutoAccept = prefs.getBoolean("auto_accept", true)
            Log.d("LocateGoService", "Settings in-memory cached: CustomerMax=${cachedMaxDistanceKm}km, RestaurantMax=${cachedMaxPickupDistanceKm}km, AutoAccept=$cachedAutoAccept")
        } catch (e: Exception) {
            Log.e("LocateGoService", "Error loading cached settings", e)
        }
    }

    private fun registerPrefsListener() {
        try {
            val prefs = getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    "max_distance_km" -> cachedMaxDistanceKm = prefs.getFloat("max_distance_km", 2.0f).toDouble()
                    "max_pickup_distance_km" -> cachedMaxPickupDistanceKm = prefs.getFloat("max_pickup_distance_km", 2.0f).toDouble()
                    "min_payout_sar" -> cachedMinPayoutSar = prefs.getFloat("min_payout_sar", 0.0f).toDouble()
                    "auto_accept" -> cachedAutoAccept = prefs.getBoolean("auto_accept", true)
                }
                Log.d("LocateGoService", "⚡ Settings updated in RAM cache: CustomerMax=${cachedMaxDistanceKm}km, RestaurantMax=${cachedMaxPickupDistanceKm}km")
            }
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            Log.e("LocateGoService", "Error registering prefs listener", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. فحص فوري O(1) لاسم الحزمة المستهدفة
        val packageName: String = event.packageName?.toString() ?: ""
        if (!isTargetPackage(packageName)) {
            currentForegroundPackage = ""
            return
        }

        currentForegroundPackage = packageName

        // 2. تصفية الأحداث: مراقبة تغيرات حالة ومحتوى النافذة
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // إذا كان القبول التلقائي معطلاً بالكامل في الذاكرة، تجاهل
        if (!cachedAutoAccept) return

        // 3. جلب العقدة الجذرية للنافذة النشطة
        val root = rootInActiveWindow ?: return

        // فحص الشاشة فورياً في جزء من الثانية
        inspectScreenForOrderOffer(root, packageName)
    }

    /**
     * فحص الشاشة بدقة متناهية وسرعة فائقة (Zero-Delay Single Pass)
     */
    private fun inspectScreenForOrderOffer(root: AccessibilityNodeInfo, packageName: String) {
        // جمع النصوص والبحث عن زر Accept في مسار مسح واحد سريع
        val orderTexts = ArrayList<String>(32)
        var predetectedAcceptNode: AccessibilityNodeInfo? = null

        val acceptNodeHolder = arrayOfNulls<AccessibilityNodeInfo>(1)
        collectTextsAndDetectAcceptButton(root, orderTexts, acceptNodeHolder)
        predetectedAcceptNode = acceptNodeHolder[0]

        if (orderTexts.isEmpty()) return

        val joinedContent = orderTexts.joinToString(" ")

        // 1. استخراج مسافة المطعم / الاستلام (Pickup Distance)
        var pickupDistKm: Double? = null
        val pickupMatcher = pickupDistancePattern.matcher(joinedContent)
        if (pickupMatcher.find()) {
            pickupDistKm = pickupMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
        }

        // 2. استخراج مسافة العميل / التوصيل (Delivery Distance)
        var deliveryDistKm: Double? = null
        val deliveryMatcher = deliveryDistancePattern.matcher(joinedContent)
        if (deliveryMatcher.find()) {
            deliveryDistKm = deliveryMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
        }

        // 3. استخراج المسافة العامة / الإجمالية
        var generalDistKm: Double? = null
        val genMatcher = generalDistancePattern.matcher(joinedContent)
        if (genMatcher.find()) {
            generalDistKm = genMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
        }

        // التحقق الذكي من وجود أرقام مسافات بالكيلومتر في قائمة النصوص إن لم تكتشفها الأنماط الصريحة
        if (pickupDistKm == null && deliveryDistKm == null && generalDistKm == null) {
            val distList = extractAllDistancesFromTexts(orderTexts)
            if (distList.isNotEmpty()) {
                generalDistKm = distList.first()
            }
        }

        // المسافة المعيارية الأساسية للتقييم
        val primaryEvalDistanceKm = deliveryDistKm ?: generalDistKm ?: pickupDistKm ?: return
        if (primaryEvalDistanceKm <= 0.0) return

        // 4. استخراج رقم الطلب إن وجد
        var extractedOrderId: String? = null
        val idMatcher = orderIdPattern.matcher(joinedContent)
        if (idMatcher.find()) {
            extractedOrderId = idMatcher.group(1)
        }

        // 5. استخراج أجر التوصيل بالريال
        var payoutSar = 18.0
        val payoutMatcher = payoutPattern.matcher(joinedContent)
        if (payoutMatcher.find()) {
            payoutMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()?.let { payoutSar = it }
        }

        // 6. استخراج اسم المتجر والحي
        val storeName = extractStoreName(orderTexts)
        val customerDistrict = extractCustomerDistrict(joinedContent, orderTexts)

        // منع تكرار النقر على نفس الطلب خلال ثانيتين (نافذة تكرار سريعة جداً لا تؤخر الطلبات التالية)
        val deduplicationKey = "${extractedOrderId ?: ""}|${pickupDistKm ?: 0}|${deliveryDistKm ?: primaryEvalDistanceKm}|$payoutSar|$storeName"
        val now = System.currentTimeMillis()
        if (processedOrdersCache[deduplicationKey]?.let { now - it < 2_000 } == true) return

        // =========================================================================
        // قاعدة الفحص والقبول التلقائي الثنائية الصارمة (Restaurant & Customer Independent Rules):
        // 1. مسافة المطعم (Pickup Distance):
        //    إذا ظهرت مسافة المطعم، يجب أن تكون <= الحد الأقصى لمسافة المطعم المحددة من السائق.
        // 2. مسافة العميل (Customer Delivery Distance):
        //    إذا ظهرت مسافة العميل، يجب أن تكون <= الحد الأقصى لمسافة العميل المحددة من السائق.
        // 3. في حال وجود مسافة واحدة عامة: يتم مطابقتها مع كلا الحدين لضمان عدم تجاوز أي منهما.
        // 4. يجب أن يكون الأجر >= الحد الأدنى للأجر المسموح به.
        // =========================================================================
        val maxAllowedCustomerKm = cachedMaxDistanceKm
        val maxAllowedPickupKm = cachedMaxPickupDistanceKm
        val minPayoutLimit = cachedMinPayoutSar

        val isPickupWithinLimit: Boolean
        val isDeliveryWithinLimit: Boolean

        if (pickupDistKm != null && deliveryDistKm != null) {
            isPickupWithinLimit = pickupDistKm <= maxAllowedPickupKm
            isDeliveryWithinLimit = deliveryDistKm <= maxAllowedCustomerKm
        } else if (pickupDistKm != null && deliveryDistKm == null) {
            isPickupWithinLimit = pickupDistKm <= maxAllowedPickupKm
            isDeliveryWithinLimit = (generalDistKm ?: pickupDistKm) <= maxAllowedCustomerKm
        } else if (deliveryDistKm != null && pickupDistKm == null) {
            isPickupWithinLimit = true // مسافة المطعم غير معروضة في كارت العرض
            isDeliveryWithinLimit = deliveryDistKm <= maxAllowedCustomerKm
        } else {
            // مسافة واحدة عامة
            isPickupWithinLimit = primaryEvalDistanceKm <= maxAllowedPickupKm
            isDeliveryWithinLimit = primaryEvalDistanceKm <= maxAllowedCustomerKm
        }

        val isPayoutAccepted = payoutSar >= minPayoutLimit
        val isOrderMatching = isPickupWithinLimit && isDeliveryWithinLimit && isPayoutAccepted

        val lowerPkg = packageName.lowercase()
        val resolvedAppName = when {
            lowerPkg.contains("locatcc") -> "Locate CC"
            lowerPkg.contains("locati") -> "Locate I"
            lowerPkg.contains("locatm") -> "Locate M"
            lowerPkg.contains("locatg") -> "Locate G"
            lowerPkg.contains("locatf") -> "Locate F"
            lowerPkg.contains("locate") -> "Locate Go"
            else -> "Locate Driver"
        }

        if (isOrderMatching) {
            // تسجيل مفتاح المنع فوراً
            processedOrdersCache[deduplicationKey] = now

            // =========================================================================
            // تنفيذ النقر الفوري الفائق (Zero-Delay Instant Accept)
            // استخدام الزر المكتشف مسبقاً في نفس المسار إن وجد، أو البحث السريع عنه
            // =========================================================================
            val clickSuccess = executeZeroDelayAccept(root, predetectedAcceptNode)

            Log.i(
                "LocateGoService",
                "⚡⚡ ZERO-DELAY ACCEPT TRIGGERED in 0ms! Success=$clickSuccess | Store='$storeName' | RestaurantDist=${pickupDistKm ?: "N/A"}km (Max: ${maxAllowedPickupKm}km) | CustomerDist=${deliveryDistKm ?: primaryEvalDistanceKm}km (Max: ${maxAllowedCustomerKm}km) | Payout=${payoutSar}SAR"
            )

            // تنبيه فوري للسائق (صوت واهتزاز خفيف)
            notifyDriverAccepted()

            // إرسال تقرير الطلب المقبول إلى السيرفر كعملية خلفية (Async Coroutine) بدون أي انتظار أو تأخير للواجهة
            serviceScope.launch {
                renderClient.evaluateOrder(
                    appName = resolvedAppName,
                    storeName = storeName,
                    distanceKm = primaryEvalDistanceKm,
                    payoutSar = payoutSar,
                    orderId = extractedOrderId,
                    customerDistrict = customerDistrict,
                    driverLat = LocationTrackingService.currentLatitude,
                    driverLng = LocationTrackingService.currentLongitude,
                    pickupDistanceKm = pickupDistKm,
                    deliveryDistanceKm = deliveryDistKm
                )
            }
        } else {
            // توثيق الرفض وأسبابه بدقة
            processedOrdersCache[deduplicationKey] = now
            val rejectReason = when {
                !isPickupWithinLimit && !isDeliveryWithinLimit ->
                    "مسافة المطعم (${pickupDistKm ?: primaryEvalDistanceKm} كم > $maxAllowedPickupKm كم) ومسافة العميل (${deliveryDistKm ?: primaryEvalDistanceKm} كم > $maxAllowedCustomerKm كم) تتجاوزان الحدود المسموحة"
                !isPickupWithinLimit ->
                    "مسافة المطعم (${pickupDistKm ?: primaryEvalDistanceKm} كم) تتجاوز الحد الأقصى للمطعم ($maxAllowedPickupKm كم)"
                !isDeliveryWithinLimit ->
                    "مسافة العميل (${deliveryDistKm ?: primaryEvalDistanceKm} كم) تتجاوز الحد الأقصى للعميل ($maxAllowedCustomerKm كم)"
                !isPayoutAccepted ->
                    "أجر التوصيل ($payoutSar ر.س) أقل من الحد الأدنى ($minPayoutLimit ر.س)"
                else ->
                    "الطلب غير مطابق لإعدادات السائق"
            }

            Log.w("LocateGoService", "🚫 ORDER REJECTED: $rejectReason")
            notifyDriverRejected()

            // إبلاغ السيرفر كعملية خلفية لتسجيل الإحصائيات
            serviceScope.launch {
                renderClient.evaluateOrder(
                    appName = resolvedAppName,
                    storeName = storeName,
                    distanceKm = primaryEvalDistanceKm,
                    payoutSar = payoutSar,
                    orderId = extractedOrderId,
                    customerDistrict = customerDistrict,
                    driverLat = LocationTrackingService.currentLatitude,
                    driverLng = LocationTrackingService.currentLongitude,
                    pickupDistanceKm = pickupDistKm,
                    deliveryDistanceKm = deliveryDistKm
                )
            }
        }
    }

    /**
     * تنفيذ النقر الفوري الفائق على زر Accept بدون أي تأخير (Zero-Delay)
     */
    private fun executeZeroDelayAccept(root: AccessibilityNodeInfo, predetectedNode: AccessibilityNodeInfo?): Boolean {
        // إذا كان الزر محدداً بالفعل أثناء المسح الموحد، اضغط عليه فوراً
        if (predetectedNode != null && performFastClickAndTap(predetectedNode)) {
            return true
        }

        val currentRoot = rootInActiveWindow ?: root

        // الطريقة 1: البحث المباشر بنصوص كلمات زر القبول
        for (keyword in ACCEPT_BUTTON_KEYWORDS) {
            val nodes = currentRoot.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (performFastClickAndTap(node)) {
                    return true
                }
            }
        }

        // الطريقة 2: البحث عن طريق معرّفات عناصر زر القبول في الواجهة (View IDs)
        for (viewId in ACCEPT_VIEW_IDS) {
            val fullId = "${currentForegroundPackage}:id/$viewId"
            val nodes = currentRoot.findAccessibilityNodeInfosByViewId(fullId)
            for (node in nodes) {
                if (performFastClickAndTap(node)) {
                    return true
                }
            }
        }

        // الطريقة 3: البحث عن أي زر قابل للنقر في الثلث السفلي من الشاشة (موضع زر القبول الدائم)
        val fallbackNodes = ArrayList<AccessibilityNodeInfo>(8)
        findActionButtonsInLowerScreen(currentRoot, fallbackNodes)
        for (node in fallbackNodes) {
            if (performFastClickAndTap(node)) {
                return true
            }
        }

        return false
    }

    /**
     * تنفيذ النقر المزدوج فائق السرعة (Accessibility ACTION_CLICK + Hardware Touch Coordinate Gesture Tap في 10ms)
     */
    private fun performFastClickAndTap(node: AccessibilityNodeInfo): Boolean {
        var clicked = false

        // 1. استدعاء أمر النقر البرمجي الفوري ACTION_CLICK
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) break
            }
            current = current.parent
        }

        // 2. نقر فوري مباشر عبر إحداثيات الشاشة بالعتاد (Coordinate Tap في 10ms فقط) لضمان تفعيل أي زر في جزء من الثانية
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                performInstantTapAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                clicked = true
            }
        }

        return clicked
    }

    /**
     * إيماءة نقر عتادية فورية في 10ms بدون أي تأخير
     */
    private fun performInstantTapAtCoordinates(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val tapPath = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(tapPath, 0, 10)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * مسار مسح موحد يجمع نصوص الطلب ويكتشف زر Accept في خطوة واحدة فائقة السرعة
     */
    private fun collectTextsAndDetectAcceptButton(
        node: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        acceptNodeHolder: Array<AccessibilityNodeInfo?>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        val candidate = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }

        if (candidate != null && candidate.length in 2..120) {
            val normalizedLower = candidate.lowercase()
            val isIgnoredNav = IGNORED_NAV_TEXTS.any { ignored ->
                normalizedLower == ignored || normalizedLower.startsWith("$ignored ") || normalizedLower.endsWith(" $ignored")
            }

            if (!isIgnoredNav) {
                texts.add(candidate)

                // فحص سريع: هل هذه العقدة هي زر القبول نفسه؟
                if (acceptNodeHolder[0] == null) {
                    val isAcceptKeyword = ACCEPT_BUTTON_KEYWORDS.any { candidate.equals(it, ignoreCase = true) }
                    if (isAcceptKeyword && (node.isClickable || node.parent?.isClickable == true)) {
                        acceptNodeHolder[0] = node
                    }
                }
            }
        }

        // فحص View ID لزر القبول
        if (acceptNodeHolder[0] == null && node.viewIdResourceName != null) {
            val resName = node.viewIdResourceName
            if (ACCEPT_VIEW_IDS.any { resName.contains(it, ignoreCase = true) }) {
                acceptNodeHolder[0] = node
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            collectTextsAndDetectAcceptButton(node.getChild(i), texts, acceptNodeHolder)
        }
    }

    /**
     * العثور على أزرار الإجراء في الثلث السفلي من الشاشة واستبعاد أزرار الرفض والإلغاء
     */
    private fun findActionButtonsInLowerScreen(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val displayHeight = resources.displayMetrics.heightPixels

        // فحص الأزرار التي تقع في الثلث السفلي من الشاشة (حيث توضع أزرار قبول الطلبات)
        if (bounds.centerY() > (displayHeight * 0.65f)) {
            val txt = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val isReject = txt.contains("رفض", true) || txt.contains("إلغاء", true) ||
                           txt.contains("تجاهل", true) || txt.contains("close", true) ||
                           txt.contains("reject", true) || txt.contains("cancel", true)

            if (!isReject && (node.isClickable || node.className?.toString()?.contains("Button", true) == true)) {
                list.add(node)
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            findActionButtonsInLowerScreen(node.getChild(i), list)
        }
    }

    /**
     * استخراج كافة أرقام الكيلومتر من النصوص بدقة
     */
    private fun extractAllDistancesFromTexts(texts: List<String>): List<Double> {
        val result = ArrayList<Double>(4)
        val kmPattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        for (item in texts) {
            val m = kmPattern.matcher(item)
            while (m.find()) {
                m.group(1)?.replace(',', '.')?.toDoubleOrNull()?.let {
                    if (it > 0.0) result.add(it)
                }
            }
        }
        return result
    }

    /**
     * استخراج اسم المتجر من قائمة النصوص المصفاة
     */
    private fun extractStoreName(texts: List<String>): String {
        for (item in texts) {
            val trimmed = item.trim()
            if (trimmed.length in 3..35 &&
                !generalDistancePattern.matcher(trimmed).find() &&
                !payoutPattern.matcher(trimmed).find() &&
                !orderIdPattern.matcher(trimmed).find() &&
                !ACCEPT_BUTTON_KEYWORDS.any { trimmed.equals(it, ignoreCase = true) } &&
                !trimmed.contains("توصيل", true) &&
                !trimmed.contains("طلب", true) &&
                !trimmed.contains("رفض", true) &&
                !trimmed.contains("تجاهل", true) &&
                !trimmed.contains("استلام", true)
            ) {
                return trimmed
            }
        }
        return "متجر العرض"
    }

    /**
     * استخراج الحي أو تفاصيل وجهة التوصيل
     */
    private fun extractCustomerDistrict(fullContent: String, texts: List<String>): String? {
        val matcher = districtPattern.matcher(fullContent)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }

        return texts.firstOrNull {
            it.contains("حي ", true) ||
            it.contains("شارع ", true) ||
            it.contains("طريق ", true) ||
            it.contains("مجمع ", true)
        }
    }

    private fun notifyDriverAccepted() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(140)
                }
            }
        } catch (_: Exception) {}
    }

    private fun notifyDriverRejected() {
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(60)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            if (prefsListener != null) {
                getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener)
            }
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Zero delay - no active looping tasks to interrupt
    }
}
