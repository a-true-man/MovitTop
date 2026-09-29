"""
driver_pricing.py

נוסחת הערכת מחיר לנסיעת "דרייבר" (מונית שירות פרטית) בישראל,
שנגזרה בשיטת רגרסיה סטטיסטית מתוך 145 שורות מחיר אמיתיות שנאספו
מהמחירון הפומבי של driverim.online (9 ערים, ספטמבר 2026).

זו נוסחת אומדן (approximation) ולא המנוע הפנימי האמיתי של החברה.
איכות ההתאמה על הדאטה שנאסף:
    מחיר 4 מקומות מול ק"מ:              R^2 = 0.986, טעות ממוצעת (MAE) כ-14 ש"ח
    מחיר 6 מק' קטן מתוך מחיר 4 מק':      R^2 = 0.998
    מחיר 6 מק' מרווח מתוך מחיר 4 מק':    R^2 = 0.995
    מחיר 7 מק' (סיינה) = 6 מק' מרווח + 20 ש"ח בדיוק (וודא ב-145/145 דגימות)
    מחיר הלוך-חזור ("צדדים") מתוך מחיר חד-כיווני: R^2 = 0.991

תיקון ביקוש-חזרה (אפקט משני, אופציונלי): המחיר תלוי גם בסיכוי שלנהג יהיה
נוסע לדרך החזרה. צירים גדולים בין ערים ידועות (ת"א-ירושלים, בני ברק-ירושלים
וכו') זולים ב-10-20% מהצפי; יעדים מבודדים/יישובים קטנים יקרים ב-15-80%
מהצפי (המקרה הקיצוני שנמצא: ירושלים-נוקדים, +84%). לכן הפונקציה מקבלת
is_major_city_pair / is_remote_destination אופציונליים - מלא אותם רק אם יש
לאפליקציה מידע אמין על סוג היעד (למשל ממאגר ערים משלה).
"""

from dataclasses import dataclass


@dataclass
class VehiclePrices:
    seats4: int
    seats6_small: int
    seats6_spacious: int
    seats7: int


@dataclass
class PriceEstimate:
    distance_km: float
    one_way: VehiclePrices
    round_trip: VehiclePrices
    wait_time_minutes: int


def _round_to(x: float, step: int) -> int:
    return int(round(x / step) * step)


def estimate_driver_price(
    distance_km: float,
    is_major_city_pair: bool = False,
    is_remote_destination: bool = False,
    round_to: int = 10,
) -> PriceEstimate:
    """
    מחשב הערכת מחיר לנסיעת דרייבר לפי מרחק נסיעה בכביש (לא קו אווירי!).

    :param distance_km: מרחק הנסיעה בק"מ (מרחק כביש בפועל, לא קו אווירי)
    :param is_major_city_pair: True אם שני קצוות המסלול הם ערים/מוקדי ביקוש
        גדולים ומוכרים (ציר עם סבירות גבוהה לנוסע חזור). משפיע רק אם גם
        distance_km > 40 (בנסיעות קצרות דמי הפתיחה שולטים, לא סיכון החזרה).
    :param is_remote_destination: True אם היעד הוא יישוב קטן/מבודד עם סיכוי
        נמוך לנוסע חזרה. מעלה משמעותית את המחיר, לפעמים עד כפול במקרי קיצון.
    :param round_to: עיגול התוצאה ל-X ש"ח הקרובים (ברירת מחדל: 10)
    """
    if distance_km < 0:
        raise ValueError("distance_km must be non-negative")

    # שלב 1: מחיר בסיס לרכב 4 מקומות, לפי מרחק
    # price4 = 36.1 + 3.606 * km  (רגרסיה ליניארית, R^2=0.986, MAE~14 ש"ח)
    seats4 = 36.1 + 3.606 * distance_km

    # שלב 1.5: תיקון ביקוש-חזרה (אפקט משני, פחות מהימן מהנוסחה הבסיסית)
    if is_major_city_pair and distance_km > 40:
        seats4 *= 0.85
    elif is_remote_destination:
        seats4 *= 1.2

    # שלב 2: שאר סוגי הרכב, כפונקציה ליניארית של מחיר ה-4 מקומות
    seats6_small = 36.35 + 1.1768 * seats4        # R^2=0.998
    seats6_spacious = 52.89 + 1.306 * seats4       # R^2=0.995
    seats7 = seats6_spacious + 20                   # כלל קבוע, מדויק ב-100% מהמדגם

    # שלב 3: מחיר הלוך-חזור / המתנה ("צדדים")
    def round_trip_of(one_way: float) -> float:
        return 64.0 + 1.483 * one_way              # R^2=0.991

    # שלב 4: זמן המתנה מותר, מדורג לפי מרחק
    if distance_km <= 20:
        wait = 30
    elif distance_km <= 40:
        wait = 40
    elif distance_km <= 75:
        wait = 60
    elif distance_km <= 115:
        wait = 90
    elif distance_km <= 160:
        wait = 105
    else:
        wait = 120

    one_way = VehiclePrices(
        seats4=_round_to(seats4, round_to),
        seats6_small=_round_to(seats6_small, round_to),
        seats6_spacious=_round_to(seats6_spacious, round_to),
        seats7=_round_to(seats7, round_to),
    )
    round_trip = VehiclePrices(
        seats4=_round_to(round_trip_of(seats4), round_to),
        seats6_small=_round_to(round_trip_of(seats6_small), round_to),
        seats6_spacious=_round_to(round_trip_of(seats6_spacious), round_to),
        seats7=_round_to(round_trip_of(seats7), round_to),
    )

    return PriceEstimate(
        distance_km=distance_km,
        one_way=one_way,
        round_trip=round_trip,
        wait_time_minutes=wait,
    )


if __name__ == "__main__":
    print(estimate_driver_price(68))  # מרחק גרידא, בלי מידע על היעד
    print(estimate_driver_price(68, is_major_city_pair=True))  # ~ ירושלים-ת"א, בפועל 240
    print(estimate_driver_price(19, is_remote_destination=True))  # ~ ירושלים-נוקדים, בפועל 180
