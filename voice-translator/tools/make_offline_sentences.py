"""Makes the 1,000 different test sentences (500 Spanish, 500 English, 25 kinds of sentence each).

Run:  python3 tools/make_offline_sentences.py   (writes app/src/test/resources/offline_1000.tsv)
Columns: language, kind, sentence. The list is fixed (same seed every time) and has no repeats.
"""
import itertools, os, random

PER_KIND = 20
rng = random.Random(2026)

def expand(templates, slots):
    out = []
    for t in templates:
        keys = [k for k in slots if "{" + k + "}" in t]
        for vals in itertools.product(*[slots[k] for k in keys]):
            s = t
            for k, v in zip(keys, vals):
                s = s.replace("{" + k + "}", v)
            out.append(s[0].upper() + s[1:] if s[0].islower() else s)
    return out

def pick(kind, templates, slots, n=PER_KIND):
    combos = expand(templates, slots)
    rng.shuffle(combos)
    seen, out = set(), []
    for c in combos:
        if c.lower() not in seen:
            seen.add(c.lower()); out.append(c)
        if len(out) == n:
            break
    assert len(out) == n, (kind, len(out))
    return out

ES = dict(
    obj_s=["la escalera", "el casco", "el taladro", "la cuerda", "el martillo", "la llave inglesa", "el nivel",
           "la cinta métrica", "el arnés", "la radio", "el generador", "la grúa", "la camioneta", "el cable", "el tornillo grande"],
    obj_p=["los guantes", "las llaves", "los pernos", "las herramientas", "los cables", "las botas", "los lentes",
           "las cuerdas", "los aisladores", "las tuercas"],
    person=["Pedro", "el capataz", "mi hermano", "la ingeniera", "el supervisor", "Juan", "el chofer", "María",
            "el electricista", "mi compañero"],
    place=["en la obra", "en la oficina", "en el almacén", "en la torre", "en el camión", "en el hotel", "en la base",
           "arriba", "abajo", "cerca del portón"],
    time=["mañana", "hoy", "esta tarde", "el lunes", "a las ocho", "a las tres", "el viernes", "en una hora",
          "esta noche", "la próxima semana"],
    tpast=["ayer", "anoche", "el lunes", "la semana pasada", "esta mañana"],
    verb=["revisar", "traer", "limpiar", "pintar", "mover", "subir", "bajar", "guardar", "medir", "cambiar"],
    num=["dos", "tres", "cuatro", "cinco", "seis", "diez", "doce", "veinte", "cien", "cincuenta"],
    pl=["pernos", "cables", "guantes", "cascos", "tornillos", "botellas de agua", "trabajadores", "metros de cuerda",
        "bolsas de cemento", "tablas"],
    name=["Pedro", "Lucía", "Carlos", "Ana", "Miguel", "Sofía", "Jorge", "Elena", "Abhishek", "Raúl"],
    city=["Monterrey", "Guadalajara", "Madrid", "Los Ángeles", "El Paso", "San Antonio", "Bogotá", "Lima", "Houston", "Querétaro"],
    weather=["mucho calor", "mucho frío", "mucho viento", "buen tiempo", "sol"],
    gear=["el casco", "el arnés", "los guantes", "las botas", "los lentes"],
    body=["la cabeza", "la espalda", "el brazo", "la rodilla", "el estómago", "la mano", "el pie", "el cuello"],
    feel=["cansado", "cansada", "enfermo", "contento", "preocupado", "mareado"],
    fam=["esposa", "madre", "padre", "hermana", "hijo", "hija", "abuela", "tío"],
    food=["pollo con arroz", "tacos de carne", "sopa de verduras", "pescado frito", "frijoles con tortillas",
          "enchiladas verdes", "huevos con jamón", "pasta con queso"],
    drink=["agua fría", "café", "un refresco", "té caliente", "jugo de naranja"],
    item=["el boleto", "la comida", "el hotel", "el taxi", "la gasolina", "el arnés nuevo"],
    veh=["camioneta", "autobús", "taxi", "tren", "avión", "coche"],
    c1=["el camión llega tarde", "la grúa no funciona", "hace mucho viento", "faltan los pernos", "la torre está lista"],
    c2=["vamos a esperar", "podemos empezar mañana", "hay que avisar al jefe", "necesitamos más gente", "todo va bien"],
    d=["12", "27", "35", "48", "103", "7", "56", "81", "90", "64"],
    favor=["la ayuda", "el café", "esperar", "venir", "todo", "traer las herramientas"],
    apol=["no te escuché", "me equivoqué", "se me olvidó", "no lo sabía"],
)
EN = dict(
    obj_s=["the ladder", "the helmet", "the drill", "the rope", "the hammer", "the wrench", "the level",
           "the tape measure", "the harness", "the radio", "the generator", "the crane", "the van", "the cable", "the big bolt"],
    obj_p=["the gloves", "the keys", "the bolts", "the tools", "the cables", "the boots", "the glasses", "the ropes",
           "the insulators", "the nuts"],
    person=["Pedro", "the foreman", "my brother", "the engineer", "the supervisor", "John", "the driver", "Maria",
            "the electrician", "my partner"],
    place=["at the site", "in the office", "in the warehouse", "on the tower", "in the truck", "at the hotel",
           "at the base", "up there", "down here", "near the gate"],
    time=["tomorrow", "today", "this afternoon", "on Monday", "at eight", "at three", "on Friday", "in an hour",
          "tonight", "next week"],
    tpast=["yesterday", "last night", "on Monday", "last week", "this morning"],
    verb=["check", "bring", "clean", "paint", "move", "lift", "lower", "store", "measure", "change"],
    num=["two", "three", "four", "five", "six", "ten", "twelve", "twenty", "a hundred", "fifty"],
    pl=["bolts", "cables", "gloves", "helmets", "screws", "bottles of water", "workers", "meters of rope",
        "bags of cement", "boards"],
    name=["Peter", "Lucy", "Charles", "Anna", "Michael", "Sophie", "George", "Helen", "Abhishek", "Ralph"],
    city=["Houston", "Dallas", "Chicago", "Denver", "Phoenix", "Seattle", "Los Angeles", "El Paso", "San Diego", "Detroit"],
    w=["hot", "cold", "windy", "sunny", "foggy"],
    gear=["your helmet", "your harness", "your gloves", "your boots", "your glasses"],
    body=["head", "back", "arm", "knee", "stomach", "hand", "foot", "neck"],
    feel=["tired", "sick", "happy", "worried", "dizzy", "cold"],
    fam=["wife", "mother", "father", "sister", "son", "daughter", "grandmother", "uncle"],
    food=["chicken with rice", "beef tacos", "vegetable soup", "fried fish", "beans with tortillas",
          "green enchiladas", "eggs with ham", "pasta with cheese"],
    drink=["cold water", "coffee", "a soda", "hot tea", "orange juice"],
    item=["the ticket", "the food", "the hotel", "the taxi", "the gas", "the new harness"],
    veh=["van", "bus", "taxi", "train", "plane", "car"],
    c1=["the truck is late", "the crane is not working", "it is very windy", "the bolts are missing", "the tower is ready"],
    c2=["we are going to wait", "we can start tomorrow", "we have to tell the boss", "we need more people", "everything is fine"],
    d=["12", "27", "35", "48", "103", "7", "56", "81", "90", "64"],
    favor=["the help", "the coffee", "waiting", "coming", "everything", "bringing the tools"],
    apol=["I didn't hear you", "I made a mistake", "I forgot", "I didn't know"],
)

ES_KINDS = [
    ("statement now", ["{person} trabaja {place}", "{person} necesita {obj_s}", "{person} tiene {obj_p}", "Siempre dejamos {obj_p} {place}"]),
    ("statement past", ["Ayer {person} dejó {obj_s} {place}", "{person} terminó el trabajo {tpast}", "Ya revisamos {obj_p}", "Olvidé {obj_s} {place}"]),
    ("statement future", ["{person} va a {verb} {obj_s} {time}", "Vamos a {verb} {obj_p} {time}"]),
    ("question where", ["¿Dónde está {obj_s}?", "¿Dónde están {obj_p}?", "¿Dónde está {person}?"]),
    ("question when", ["¿A qué hora llega {person}?", "¿Cuándo vamos a {verb} {obj_p}?"]),
    ("question yes/no", ["¿Tienes {obj_s}?", "¿Puedes {verb} {obj_s}?", "¿Está {person} {place}?"]),
    ("polite request", ["Por favor, trae {obj_s}", "Por favor, guarda {obj_p} {place}", "¿Me pasas {obj_s}, por favor?"]),
    ("short command", ["Trae {obj_s}", "Baja {obj_s}", "Guarda {obj_p}", "Revisa {obj_s}", "Limpia {obj_p}"]),
    ("negative", ["No encuentro {obj_s}", "No tenemos {obj_p}", "{person} no viene {time}", "No toques {obj_s}"]),
    ("numbers in words", ["Necesitamos {num} {pl}", "Faltan {num} {pl}"]),
    ("times and days", ["La reunión es {time}", "El camión llega {time}", "Empezamos {time}"]),
    ("names and cities", ["{name} vive en {city}", "Vamos a {city} {time}"]),
    ("weather", ["Hoy hace {weather} {place}", "Va a llover {time}", "Hay mucha niebla {place}"]),
    ("safety", ["Cuidado con {obj_s}", "Ponte {gear} antes de subir", "No te pares debajo de {obj_s}", "Revisa {gear} otra vez"]),
    ("tools and materials", ["Necesito {obj_s} {place}", "¿Quién tiene {obj_s}?", "Se rompió {obj_s}"]),
    ("health", ["Me duele {body}", "Estoy muy {feel}", "{person} se siente mal hoy"]),
    ("family", ["Mi {fam} vive en {city}", "Mi {fam} cumple años {time}"]),
    ("food and drink", ["Hoy comemos {food}", "¿Quieres {drink}?", "Me gusta {food}"]),
    ("money", ["El pago llega {time}", "¿Cuánto cuesta {item}?", "Me deben {num} días de pago"]),
    ("transport", ["El autobús sale {time}", "Vamos en {veh} {time}"]),
    ("long sentence", ["{person} dijo que {c1}, pero {c2}"]),
    ("numbers in digits", ["La torre {d} está lista", "Necesitamos {d} metros de cable", "El camión {d} llega {time}"]),
    ("greeting", ["Hola {name}, ¿cómo estás?", "Buenas tardes, {name}", "Adiós {name}, nos vemos {time}"]),
    ("thanks and sorry", ["Gracias por {favor}", "Lo siento, {apol}", "Perdón, {name}, {apol}"]),
]
EN_KINDS = [
    ("statement now", ["{person} works {place}", "{person} needs {obj_s}", "{person} has {obj_p}", "We always leave {obj_p} {place}"]),
    ("statement past", ["Yesterday {person} left {obj_s} {place}", "{person} finished the job {tpast}", "We already checked {obj_p}", "I forgot {obj_s} {place}"]),
    ("statement future", ["{person} is going to {verb} {obj_s} {time}", "We are going to {verb} {obj_p} {time}"]),
    ("question where", ["Where is {obj_s}?", "Where are {obj_p}?", "Where is {person}?"]),
    ("question when", ["What time does {person} arrive?", "When are we going to {verb} {obj_p}?"]),
    ("question yes/no", ["Do you have {obj_s}?", "Can you {verb} {obj_s}?", "Is {person} {place}?"]),
    ("polite request", ["Please bring {obj_s}", "Please put {obj_p} {place}", "Can you pass me {obj_s}, please?"]),
    ("short command", ["Bring {obj_s}", "Lower {obj_s}", "Store {obj_p}", "Check {obj_s}", "Clean {obj_p}"]),
    ("negative", ["I can't find {obj_s}", "We don't have {obj_p}", "{person} is not coming {time}", "Don't touch {obj_s}"]),
    ("numbers in words", ["We need {num} {pl}", "We are missing {num} {pl}"]),
    ("times and days", ["The meeting is {time}", "The truck arrives {time}", "We start {time}"]),
    ("names and cities", ["{name} lives in {city}", "We are going to {city} {time}"]),
    ("weather", ["It is very {w} {place}", "It is going to rain {time}", "There is a lot of fog {place}"]),
    ("safety", ["Be careful with {obj_s}", "Put on {gear} before climbing", "Do not stand under {obj_s}", "Check {gear} again"]),
    ("tools and materials", ["I need {obj_s} {place}", "Who has {obj_s}?", "{obj_s} broke"]),
    ("health", ["My {body} hurts", "I am very {feel}", "{person} feels sick today"]),
    ("family", ["My {fam} lives in {city}", "My {fam}'s birthday is {time}"]),
    ("food and drink", ["Today we eat {food}", "Do you want {drink}?", "I like {food}"]),
    ("money", ["The payment arrives {time}", "How much is {item}?", "They owe me {num} days of pay"]),
    ("transport", ["The bus leaves {time}", "We are going by {veh} {time}"]),
    ("long sentence", ["{person} said that {c1}, but {c2}"]),
    ("numbers in digits", ["Tower {d} is ready", "We need {d} meters of cable", "Truck {d} arrives {time}"]),
    ("greeting", ["Hi {name}, how are you?", "Good afternoon, {name}", "Bye {name}, see you {time}"]),
    ("thanks and sorry", ["Thanks for {favor}", "I'm sorry, {apol}", "Sorry, {name}, {apol}"]),
]
SHORT_ES = ["Sí, claro", "Muchas gracias", "De nada", "Está bien", "Ya voy", "Un momento", "Buenos días", "Buenas noches",
            "Hasta mañana", "Con permiso", "Perdón", "Claro que sí", "No sé", "Tal vez", "Ahorita voy", "Qué bueno",
            "Me parece bien", "Por supuesto", "Vale", "Todo bien"]
SHORT_EN = ["Yes, of course", "Thank you very much", "You're welcome", "That's fine", "I'm coming", "One moment",
            "Good morning", "Good night", "See you tomorrow", "Excuse me", "Sorry", "Sure thing", "I don't know", "Maybe",
            "On my way", "That's great", "Sounds good", "Of course", "Okay then", "All good"]

rows = []
for lang, kinds, slots, short in (("ES", ES_KINDS, ES, SHORT_ES), ("EN", EN_KINDS, EN, SHORT_EN)):
    for kind, templates in kinds:
        rows += [(lang, kind, s) for s in pick(kind, templates, slots)]
    rows += [(lang, "short reply", s) for s in short]
assert len(rows) == 1000, len(rows)
assert len({r[2].lower() for r in rows}) == 1000, "repeated sentence"
here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "app", "src", "test", "resources", "offline_1000.tsv")
with open(out, "w", encoding="utf-8") as f:
    for r in rows:
        f.write("\t".join(r) + "\n")
print("wrote", len(rows), "sentences,", len({(r[0], r[1]) for r in rows}), "kinds")
