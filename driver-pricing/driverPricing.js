/**
 * driverPricing.js
 *
 * נוסחת הערכת מחיר לנסיעת "דרייבר" (מונית שירות פרטית) בישראל,
 * שנגזרה בשיטת רגרסיה סטטיסטית מתוך 145 שורות מחיר אמיתיות שנאספו
 * מהמחירון הפומבי של driverim.online (9 ערים, ספטמבר 2026).
 *
 * הערה חשובה: זו נוסחת אומדן (approximation), לא המנוע הפנימי האמיתי
 * של החברה. איכות ההתאמה על הדאטה שנאסף:
 *   - מחיר 4 מקומות מול ק"מ:              R² = 0.986, טעות ממוצעת ≈ 14 ₪
 *   - מחיר 6 מק' קטן מתוך מחיר 4 מק':      R² = 0.998
 *   - מחיר 6 מק' מרווח מתוך מחיר 4 מק':    R² = 0.995
 *   - מחיר 7 מק' (סיינה) = 6 מק' מרווח + 20 ₪ בדיוק (ב-145/145 מהדגימות, ללא יוצא מן הכלל)
 *   - מחיר הלוך-חזור ("צדדים") מתוך מחיר חד-כיווני: R² = 0.991
 *
 * תיקון ביקוש-חזרה (אפקט משני, אופציונלי): נמצא שמחיר תלוי גם בסיכוי
 * שלנהג יהיה נוסע לדרך החזרה. צירים גדולים בין ערים ידועות (ת"א-ירושלים,
 * בני ברק-ירושלים וכו') זולים ב-10-20% מהצפי; יעדים מבודדים/יישובים קטנים
 * יקרים ב-15-80% מהצפי (המקרה הקיצוני שנמצא: ירושלים-נוקדים, +84%).
 * לכן הפונקציה מקבלת isMajorCityPair / isRemoteDestination אופציונליים —
 * מלא אותם רק אם יש לאפליקציה מידע אמין על סוג היעד (למשל ממאגר ערים משלה).
 */

/**
 * מחשב הערכת מחיר לנסיעת דרייבר לפי מרחק נסיעה בכביש (לא קו אווירי!).
 * @param {number} distanceKm - מרחק הנסיעה בק"מ (מרחק כביש בפועל, לא קו אווירי)
 * @param {object} [opts]
 * @param {boolean} [opts.isMajorCityPair=false] - true אם שני קצוות המסלול הם ערים/מוקדי ביקוש גדולים
 *        ומוכרים (ציר עם סבירות גבוהה לנוסע חזור, כמו ירושלים-בני ברק). משפיע רק אם גם distanceKm > 40,
 *        כי באפקט הזה בנסיעות קצרות דמי הפתיחה שולטים ולא סיכון החזרה.
 * @param {boolean} [opts.isRemoteDestination=false] - true אם היעד הוא יישוב קטן/מבודד עם סיכוי נמוך
 *        לנוסע חזרה (למשל התנחלות קטנה). מעלה משמעותית את המחיר, לפעמים עד כפול במקרי קיצון.
 * @param {number} [opts.roundTo=10] - עיגול התוצאה ל-X ₪ הקרובים (ברירת מחדל: 10)
 * @returns {{
 *   distanceKm: number,
 *   oneWay: { seats4: number, seats6Small: number, seats6Spacious: number, seats7: number },
 *   roundTrip: { seats4: number, seats6Small: number, seats6Spacious: number, seats7: number },
 *   waitTimeMinutes: number
 * }}
 */
function estimateDriverPrice(distanceKm, opts = {}) {
  const {
    isMajorCityPair = false,
    isRemoteDestination = false,
    roundTo = 10,
  } = opts;

  if (typeof distanceKm !== "number" || distanceKm < 0) {
    throw new Error("distanceKm must be a non-negative number");
  }

  const round = (x) => Math.round(x / roundTo) * roundTo;

  // --- שלב 1: מחיר בסיס לרכב 4 מקומות, לפי מרחק ---
  // price4 = 36.1 + 3.606 * km   (נגזר מרגרסיה ליניארית, R²=0.986, MAE~14 ₪)
  let seats4 = 36.1 + 3.606 * distanceKm;

  // --- שלב 1.5: תיקון ביקוש-חזרה (אפקט משני, פחות מהימן מהנוסחה הבסיסית) ---
  // אושש אמפירית: צירי-פנדלרים גדולים (ת"א/ירושלים/בני-ברק/אשדוד...) זולים בכ-10-20%
  // מהצפי, ויעדים מבודדים יקרים בכ-15-80% מהצפי. משתמשים בזה רק אם יש מידע אמין
  // על סוג היעד (לדוגמה ממאגר ערים/יישובים באפליקציה עצמה).
  if (isMajorCityPair && distanceKm > 40) {
    seats4 *= 0.85;
  } else if (isRemoteDestination) {
    seats4 *= 1.2;
  }

  // --- שלב 2: שאר סוגי הרכב, כפונקציה ליניארית של מחיר ה-4 מקומות ---
  // (ההתאמה הזו הרבה יותר מדויקת מאשר לחשב כל אחד ישירות לפי ק"מ)
  const seats6Small = 36.35 + 1.1768 * seats4; // R²=0.998
  const seats6Spacious = 52.89 + 1.306 * seats4; // R²=0.995
  const seats7 = seats6Spacious + 20; // כלל קבוע ומדויק ב-100% מהמדגם

  // --- שלב 3: מחיר הלוך-חזור / המתנה ("צדדים") ---
  // roundTrip = 64.0 + 1.483 * oneWay   (R²=0.991)
  const roundTripOf = (oneWay) => 64.0 + 1.483 * oneWay;

  // --- שלב 4: זמן המתנה מותר, מדורג לפי מרחק (מבוסס על התבניות שנצפו בדאטה) ---
  let waitTimeMinutes;
  if (distanceKm <= 20) waitTimeMinutes = 30;
  else if (distanceKm <= 40) waitTimeMinutes = 40;
  else if (distanceKm <= 75) waitTimeMinutes = 60;
  else if (distanceKm <= 115) waitTimeMinutes = 90;
  else if (distanceKm <= 160) waitTimeMinutes = 105;
  else waitTimeMinutes = 120;

  return {
    distanceKm,
    oneWay: {
      seats4: round(seats4),
      seats6Small: round(seats6Small),
      seats6Spacious: round(seats6Spacious),
      seats7: round(seats7),
    },
    roundTrip: {
      seats4: round(roundTripOf(seats4)),
      seats6Small: round(roundTripOf(seats6Small)),
      seats6Spacious: round(roundTripOf(seats6Spacious)),
      seats7: round(roundTripOf(seats7)),
    },
    waitTimeMinutes,
  };
}

module.exports = { estimateDriverPrice };

// --- דוגמת שימוש ---
if (require.main === module) {
  console.log(estimateDriverPrice(68)); // מרחק גרידא, בלי מידע על היעד
  console.log(estimateDriverPrice(68, { isMajorCityPair: true })); // ~ ירושלים-תל אביב, בפועל ₪240
  console.log(estimateDriverPrice(19, { isRemoteDestination: true })); // ~ ירושלים-נוקדים, בפועל ₪180
}
