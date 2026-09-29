# עדכון נתוני תחבורה אוטומטי ב-GitLab

מסמך זה מסביר מה האוטומציה עושה, למה, ואיך להקים אותה — כולל למה זה לא
"הורדת קובץ" פשוטה כפי שאולי דמיינת.

## מה בעצם צריך להתעדכן

האפליקציה **לא מורידה נתונים ברשת בזמן ריצה**. מסך "ייבוא נתונים" שלה
(`DataImportActivity`) מייבא קובץ מקומי בלבד — מ-USB/כרטיס SD. אז "עדכון
שבועי" אומר בפועל: להכין מראש חבילת קובץ טרייה שאתה תוריד ותייבא ידנית
למכשיר (או תוסיף בעתיד תמיכה בהורדה מתוך האפליקציה עצמה — זה לא קיים היום).

מנוע הניתוב (MOTIS, `libmotis.so`) הוא קוד קומפילד וכבר נמצא בריפו — הוא
**לא** משתנה בעדכון שבועי. מה שכן חייב להתעדכן זה ה**גרף** שהמנוע קורא —
קובץ בינארי שנבנה מחדש מתוך קובץ ה-GTFS (לוחות הזמנים הארציים) + מפת
OSM, בפקודת `motis import`. GTFS משתנה כל שבוע (קווים, תדירויות, לו"ז
חגים וכו'), ולכן חובה להריץ את כל שרשרת העיבוד מחדש כל פעם, לא רק
"להוריד קובץ":

```
GTFS חדש → optimize_gtfs.py → compile_motis_graph.sh (motis import) → build_line_schedules.py → package_motis_data.sh
```

זה בדיוק מה ש-`.gitlab-ci.yml` שנוסף לריפו עושה, פעם בשבוע.

## גודל הנתונים — ולמה זה לא פרסום GitLab Pages רגיל

הגרף המקומפל שוקל כ-**1.7GB** (נבדק על המכונה הזו). זה מעל המגבלה
הסטנדרטית ל-artifact של job ולפריסת Pages ב-GitLab.com בתוכנית החינמית
(סביב 1GB). לכן הפייפליין **לא** שם את החבילה בתוך `public/` של Pages —
הוא מעלה אותה ל-**Package Registry** של הפרויקט (שאין לו מגבלת גודל כזו),
ודף ה-Pages שמתפרסם הוא רק עמוד HTML קטן עם קישור הורדה לגרסה העדכנית.

## שלבי ההקמה (חד-פעמי)

1. **צור פרויקט ב-GitLab** (gitlab.com, חינמי מספיק): New project → Create
   blank project. שם מוצע: `movitop`.
2. **הוסף אותו כ-remote ודחוף את הריפו:**
   ```bash
   git remote add gitlab https://gitlab.com/<your-username>/movitop.git
   git push -u gitlab movitop-android-v2:main
   ```
   (השתמשתי בברנץ' `movitop-android-v2` כדי לא לדרוס היסטוריה קיימת
   ב-GitHub — ב-GitLab, שזה פרויקט חדש וריק, אפשר לדחוף אותו ישר בתור
   `main`.)
3. **ודא ש-GitLab Pages מופעל:** Settings → General → Visibility, features
   → General pipelines → ודא ש-CI/CD מופעל. Pages מופעל אוטומטית ברגע
   שקיים job בשם `pages` שמפיק artifact בתיקיית `public/` (זה כבר קיים
   ב-`.gitlab-ci.yml`).
4. **הגדל את ה-timeout של הפייפליין** (השלב הכבד — `motis import` — יכול
   לקחת זמן): Settings → CI/CD → General pipelines → Timeout, למשל שעה
   וחצי-שעתיים.
5. **צור Pipeline Schedule שבועי:** Build → Pipeline schedules → New
   schedule:
   - Description: `Weekly transit data refresh`
   - Interval pattern (custom): `0 19 * * 6`
   - Cron timezone: `Jerusalem` (מוצג ברשימה בתור `(GMT+02:00) Jerusalem`)
   - Target branch: `main`
   - Active: ✓

   `6` ב-cron הוא יום שבת (0=ראשון). זו שעה קבועה — 19:00 — לא זמן צאת
   שבת האסטרונומי, בדיוק כפי שביקשת.
6. **בדיקה ידנית:** באותו מסך Pipeline schedules יש כפתור "Run" ליד
   הסקדיול — מריץ אותו מיד, בלי לחכות לשבת, כדי לוודא שהכול עובד.

## מגבלות שכדאי לדעת עליהן

- **דקות CI חינמיות:** בתוכנית החינמית של GitLab.com יש מכסת דקות
  חודשית ל-shared runners. ריצה שבועית אחת אמורה להיכנס בנוחות, אבל אם
  המכסה אוזלת — אפשר לחבר runner עצמאי (self-hosted) בחינם.
- **Docker-in-Docker:** שלב קומפילציית הגרף (`compile_motis_graph.sh`)
  מריץ `docker run` בתוך ה-job, ולכן ה-pipeline מגדיר `services:
  docker:24-dind`. זה נתמך ב-shared runners של GitLab.com "out of the
  box". אם בעתיד תעבור ל-runner עצמאי, ודא שהוא מוגדר במצב `privileged`.
- **זמן ריצה:** הורדת GTFS+OSM ועיבודם עלולים לקחת בין כמה דקות לכמה
  עשרות דקות, תלוי במשאבי ה-runner. אם ה-job נכשל ב-timeout — הגדל אותו
  (שלב 4 למעלה).
- **קובץ ה-OSM (‏~120MB)** מוזרם ב-cache בין ריצות ולא מורד מחדש כל שבוע
  (הוא כמעט לא משתנה) — רק ה-GTFS מתעדכן בכל ריצה.

## מה קורה בפועל בכל יום שבת ב-19:00

1. GitLab מריץ את `refresh-transit-data`: מוריד GTFS טרי, מריץ את כל
   שרשרת העיבוד, ומעלה את `movitop-data-YYYYMMDD.zip` ל-Package Registry.
2. GitLab מריץ את `pages`: מפרסם עמוד HTML קטן (`gitlab-pages/index.template.html`)
   עם תאריך העדכון וקישור הורדה לחבילה העדכנית.
3. אתה נכנס לעמוד ה-Pages של הפרויקט (הכתובת מופיעה ב-Settings → Pages
   אחרי הפריסה הראשונה), לוחץ הורדה, ומייבא את הקובץ למכשיר דרך מסך
   "ייבוא נתונים מעודכן" באפליקציה.
