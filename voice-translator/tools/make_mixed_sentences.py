"""Makes 1,000 different sentences that mix Spanish and English in the same sentence (Spanglish).

Run:  python3 tools/make_mixed_sentences.py   (writes app/src/test/resources/mixed_1000.tsv)
Columns: frame (ES = Spanish sentence with English words, EN = English sentence with Spanish words), sentence.
"""
import itertools, os, random

rng = random.Random(1717)
S = dict(
    en_tool=["ladder", "helmet", "drill", "rope", "hammer", "wrench", "level", "harness", "crane", "tape", "bolt"],
    es_time=["mañana", "hoy", "esta tarde", "el lunes", "a las ocho", "el viernes", "esta noche", "en una hora",
             "la próxima semana", "a las tres", "el martes", "el jueves", "el sábado", "en la tarde", "ahorita"],
    es_clause=["el camión llega tarde", "la grúa no funciona", "hace mucho viento", "faltan los pernos",
               "la torre está lista", "necesitamos más gente", "vamos a esperar", "todo va bien",
               "el jefe no viene", "la camioneta está rota", "hay mucho trabajo", "ya terminamos la base",
               "no tenemos agua", "el generador no sirve", "llueve mucho", "estamos cansados"],
    es_place=["en la obra", "en la oficina", "en el almacén", "arriba", "abajo", "en la torre",
              "en el camión", "en la base", "cerca del portón", "en el hotel"],
    en_role=["foreman", "driver", "engineer", "electrician", "boss", "partner", "brother", "sister"],
    en_vehicle=["truck", "car", "train", "plane"],
    en_obj=["bolts", "gloves", "keys", "tools", "boots", "glasses", "nuts", "boards", "screws", "bags", "bottles"],
    en_verb=["check", "clean", "move", "paint", "lift", "store", "bring", "measure"],
    es_family=["hermano", "hermana", "padre", "madre", "esposa", "hijo", "hija", "abuela"],
    en_time=["tomorrow", "tonight", "today", "on Monday", "on Friday", "this afternoon", "next week", "in an hour"],
    es_material=["cemento", "tornillos", "cuerdas", "guantes", "pernos", "tablas", "botas", "llaves", "lentes",
                 "tuercas", "bolsas"],
    en_place=["tower", "site", "office", "warehouse", "gate", "truck"],
    es_role=["capataz", "jefe", "ingeniero", "chofer", "ingeniera", "electricista"],
    es_name=["Pedro", "María", "Juan", "Lucía", "Carlos", "Ana", "Miguel", "Sofía", "Jorge", "Elena", "Raúl", "Rosa"],
    es_obj_p=["los guantes", "las llaves", "los pernos", "las botas", "los lentes", "las cuerdas", "las tuercas",
              "los cascos", "las herramientas"],
)
ES_FRAME = [
    "Necesito el {en_tool} para {es_time}, okay?",
    "Pásame the {en_tool}, por favor",
    "Dile al {en_role} que {es_clause}, okay?",
    "El {en_vehicle} está {es_place} right now",
    "Vamos a check the {en_obj} {es_time}",
    "Okay, I know, pero {es_clause}",
    "No sé where is the {en_tool}",
    "Mi {es_family} is coming {en_time}",
    "¿Dónde está the {en_tool}, amigo?",
    "Hoy vamos a {en_verb} the {en_obj}",
    "Llama al {en_role} y dile that {es_clause}",
]
EN_FRAME = [
    "We need más {es_material} for the {en_place}",
    "The {en_tool} está {es_place}",
    "Where is el {es_role}?",
    "I think que {es_clause}",
    "Call the {en_role} mañana, please",
    "Thank you, gracias, {es_name}",
    "The meeting es {es_time}",
    "Bring the {en_tool} y {es_obj_p}",
    "Please bring {es_obj_p} to the {en_place}",
    "My {es_family} is here, gracias",
    "The {en_obj} are {es_place}",
]

def expand(templates):
    out = []
    for t in templates:
        keys = [k for k in S if "{" + k + "}" in t]
        for vals in itertools.product(*[S[k] for k in keys]):
            s = t
            for k, v in zip(keys, vals):
                s = s.replace("{" + k + "}", v)
            out.append(s)
    return out

rows = []
for frame, templates in (("ES", ES_FRAME), ("EN", EN_FRAME)):
    per = {t: expand([t]) for t in templates}
    # As even a spread over the templates as their sizes allow: 500 per frame.
    pool, left = [], 500
    order = sorted(templates, key=lambda t: len(per[t]))
    for i, t in enumerate(order):
        items = per[t][:]
        rng.shuffle(items)
        share = left // (len(order) - i)
        take = items[:min(len(items), share)]
        pool += take
        left -= len(take)
    assert left == 0, left
    rows += [(frame, s) for s in pool]
assert len(rows) == 1000 and len({r[1].lower() for r in rows}) == 1000
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "test", "resources", "mixed_1000.tsv")
with open(out, "w", encoding="utf-8") as f:
    for r in rows:
        f.write("\t".join(r) + "\n")
print("wrote", len(rows), "mixed sentences")
