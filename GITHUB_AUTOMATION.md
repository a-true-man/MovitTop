# עדכון נתוני תחבורה אוטומטי ב-GitHub

מסמך זה מסביר מה האוטומציה עושה, למה, ואיך היא מוגדרת.

## מה בעצם צריך להתעדכן

האפליקציה **לא מורידה נתונים ברשת בזמן ריצה**. מסך "ייבוא נתונים" שלה
(`DataImportActivity`) מייבא קובץ מקומי בלבד — מ-USB/כרטיס SD. אז "עדכון
שבועי" אומר בפועל: להכין מראש חבילת קובץ טרייה שתוריד ותייבא ידנית
למכשיר.

מנוע הניתוב (MOTIS, `libmotis.so`) הוא בינארי קומפיילד שכבר נמצא בריפו —
הוא **לא** משתנה בעדכון שבועי. מה שכן חייב להתעדכן זה ה**גרף** שהמנוע
קורא — קובץ בינארי שנבנה מחדש מתוך קובץ ה-GTFS (לוחות הזמנים הארציים) +
מפת OSM, בפקודת `motis import`. GTFS משתנה כל שבוע, ולכן חובה להריץ את כל
שרשרת העיבוד מחדש כל פעם:

```
GTFS חדש → optimize_gtfs.py → compile_motis_graph.sh (motis import) → build_line_schedules.py → package_motis_data.sh
```

זה בדיוק מה ש-`.github/workflows/weekly-transit-data.yml` עושה, פעם
בשבוע, אוטומטית.

## איפה זה רץ ואיפה מתפרסם

- **הפעלה:** GitHub Actions, על הרפו `a-true-man/MovitTop` (ברנץ' ברירת
  המחדל `movitop-android-v2` — סקדולינג רץ רק מברנץ' ברירת המחדל).
- **זמן:** כל מוצאי שבת, 19:00 שעון ישראל. ל-GitHub Actions אין תמיכה
  ב-cron עם אזור זמן — הוא תמיד UTC — אז יש שני טריגרים (אחד ל-UTC+3
  בקיץ, אחד ל-UTC+2 בחורף), וצעד ראשון בג'וב בודק את השעה המקומית
  בפועל ומדלג אם זו לא השעה 19 — כך שבפועל רק ריצה אחת בפועל "אמיתית"
  בכל שבוע, כל השנה.
- **פרסום הקובץ:** ל-**GitHub Release** קבוע בשם `transit-data-latest`
  (מתעדכן — לא נוצר release חדש כל שבוע, אותו אחד מתעדכן), כי קבצי
  Release תומכים בקבצים גדולים (עד 2GB) בלי הבעיות של artifacts/Pages
  רגילים. קישור ההורדה הקבוע:
  ```
  https://github.com/a-true-man/MovitTop/releases/download/transit-data-latest/movitop-data-latest.zip
  ```
- **דף אינטרנט:** עמוד HTML קטן (`github-pages/index.template.html`)
  מתפרסם אוטומטית לברנץ' `gh-pages`, עם תאריך העדכון האחרון וקישור
  הורדה. אחרי הריצה הראשונה יש להפעיל את GitHub Pages בפרויקט (חד-פעמי):
  **Settings → Pages → Source: Deploy from a branch → Branch: `gh-pages`
  / `(root)`**. אחרי זה הכתובת תהיה
  `https://a-true-man.github.io/MovitTop/`.

## הרצה ידנית לבדיקה

מכיוון שזה ריפו פרטי, יש לך מכסת דקות חינמית חודשית ל-Actions (2,000
דקות/חודש בתוכנית ה-Free הסטנדרטית) — ריצה שבועית אחת (כמה עשרות דקות)
נכנסת בנוחות בתוכה.

להרצה ידנית מיידית (בלי לחכות למוצ"ש):
```bash
gh workflow run weekly-transit-data.yml
gh run watch   # עוקב אחרי הריצה החיה
```
או דרך הדפדפן: Actions → Weekly transit data refresh → Run workflow.

## מגבלות שכדאי לדעת עליהן

- **דיסק ב-runner:** ה-runner של GitHub מספק בערך 14GB פנויים — סכום כל
  הקבצים (GTFS ~180MB, OSM ~120MB, GTFS מותאם ~180MB, גרף מקומפל ~1.7GB,
  חבילה ארוזה ~1.5GB) נכנס בנוחות.
- **Docker:** בניגוד ל-GitLab, ל-runner של GitHub יש Docker מוכן וזמין
  ישירות — אין צורך בהגדרת docker-in-docker נפרדת.
- **זמן ריצה:** ה-timeout מוגדר ל-120 דקות; אם התהליך המלא (הורדה +
  עיבוד + קומפילציה) לוקח יותר, אפשר להגדיל אותו ב-`.github/workflows/weekly-transit-data.yml`.
- **קובץ ה-OSM (~120MB)** נשמר ב-cache בין ריצות ולא מורד מחדש כל שבוע —
  רק ה-GTFS מתעדכן בכל ריצה.

## מה קורה בפועל בכל מוצאי שבת ב-19:00

1. GitHub Actions מריץ את `refresh-transit-data`: מוריד GTFS טרי, מריץ
   את כל שרשרת העיבוד, ומעדכן את ה-Release `transit-data-latest` עם
   `movitop-data-latest.zip`.
2. אותה ריצה מפרסמת עמוד HTML קטן (מ-`github-pages/index.template.html`)
   לברנץ' `gh-pages`, שמוצג ב-GitHub Pages.
3. אתה נכנס לעמוד ה-Pages (`https://a-true-man.github.io/MovitTop/`),
   לוחץ הורדה, ומייבא את הקובץ למכשיר דרך מסך "ייבוא נתונים מעודכן"
   באפליקציה.
