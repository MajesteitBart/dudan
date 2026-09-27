import { readFileSync } from "node:fs";

// Scripted OpenUI replies for the mock server. They only come back when the app sent aight's
// OpenUI instructions (Settings > Replies and files > Rich replies), as a real agent would behave.
//   "compare" / "vergelijk"  -> table, bar chart, callout, follow-ups
//   "form" / "formulier"     -> a booking form with validation; submitting it gets a plain answer
//   "chart" / "grafiek"      -> line, area, pie and stacked charts in tabs
//   "steps" / "stappen"      -> steps, accordion, entity list, tags, list with actions

const compare = `Hier zijn de drie goedkoopste vaste contracten voor jouw verbruik.

\`\`\`openui-lang
root = Card([header, table, chart, note, followUps], sources)
header = CardHeader("Energiecontracten", "Vast, 1 jaar, 2.900 kWh")
table = Table([Col("Leverancier", suppliers), Col("Per maand", monthly, "number"), Col("Groen", green)])
suppliers = ["Leverancier A", "Leverancier B", "Leverancier C"]
monthly = ["€142", "€148", "€156"]
green = @Each(["ja", "deels", "nee"], "g", Tag(g, null, "sm", g == "ja" ? "success" : "neutral"))
chart = BarChart(suppliers, [Series("€ per maand", [142, 148, 156]), Series("€ vorig jaar", [151, 149, 170])], "grouped")
note = Callout("info", "Eerder opzeggen", "Alle drie rekenen €125 als je binnen het jaar opzegt [1].")
followUps = FollowUpBlock([f1, f2, f3])
f1 = FollowUpItem("Welke is het groenst?")
f2 = FollowUpItem("Zet me over naar Leverancier A")
f3 = FollowUpItem("Wat kost dynamisch?")
sources = [{title: "Opzegvergoedingen 2026", sourceName: "ACM", url: "https://www.acm.nl"}]
\`\`\`

Leverancier A is het goedkoopst en scheelt je €168 per jaar.`;

const form = `Vul dit in, dan reserveer ik.

\`\`\`openui-lang
root = Card([header, booking])
header = CardHeader("Tafel reserveren", "Restaurant De Kas, Amsterdam")
booking = Form("booking", buttons, [dateField, peopleField, timeField, wishes, email, extras])
dateField = FormControl("Datum", DatePicker("date"))
peopleField = FormControl("Personen", Select("people", [SelectItem("2", "2 personen"), SelectItem("4", "4 personen"), SelectItem("6", "6 personen")], "Hoeveel?", {required: true}))
timeField = FormControl("Tijd", Chips("time", "single", [ChipItem("18", "18:00"), ChipItem("19", "19:00"), ChipItem("20", "20:30")]))
wishes = FormControl("Wensen", TextArea("wishes", "Raamtafel, allergieën…", 3), "Optioneel")
email = FormControl("E-mail", Input("email", "jij@voorbeeld.nl", "email", {required: true, email: true}))
extras = FormControl("Extra", SwitchGroup("extras", [SwitchItem("Aperitief bij aankomst", null, "aperitif"), SwitchItem("Verjaardag", "We zetten een kaarsje op het dessert", "birthday", true)]))
buttons = Buttons([Button("Reserveren", Action([@ToAssistant("Reserveer deze tafel")]), "primary"), Button("Toch niet", Action([@ToAssistant("Laat maar")]), "secondary")])
\`\`\``;

const chart = `Zo ontwikkelde je verbruik zich dit jaar.

\`\`\`openui-lang
root = Card([header, tabs])
header = CardHeader("Verbruik 2026", "Stroom en gas per maand")
tabs = Tabs([t1, t2, t3])
t1 = TabItem("line", "Trend", [line])
t2 = TabItem("area", "Cumulatief", [area])
t3 = TabItem("share", "Verdeling", [pie, stacked])
months = ["jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep"]
line = LineChart(months, [Series("Stroom (kWh)", [320, 290, 260, 230, 200, 190, 185, 195, 240]), Series("Gas (m³)", [210, 190, 150, 90, 40, 20, 15, 18, 60])], "natural")
area = AreaChart(months, [Series("Stroom (kWh)", [320, 610, 870, 1100, 1300, 1490, 1675, 1870, 2110])])
pie = PieChart(["Verwarming", "Warm water", "Koken", "Apparaten"], [52, 18, 6, 24], "donut")
stacked = SingleStackedBarChart(["Zon", "Wind", "Gas", "Kolen"], [34, 41, 20, 5])
\`\`\``;

const steps = `Zo zet je de upload-service op.

\`\`\`openui-lang
root = Card([header, how, details, facts, tags, pick])
header = CardHeader("Uploads instellen", "Op de Hermes-server")
how = Steps([s1, s2, s3])
s1 = StepsItem("Kopieer het script", "Zet \`aight_upload.py\` in \`~/.hermes/\`.")
s2 = StepsItem("Start de service", "\`systemctl --user enable --now aight-upload\`")
s3 = StepsItem("Controleer", "Tik in aight op **Uploadservice controleren**.")
details = Accordion([AccordionItem("why", "Waarom een aparte service?", [TextContent("Hermes neemt alleen afbeeldingen aan en maximaal 10 MB per verzoek.")]), AccordionItem("where", "Waar komen de bestanden?", [CodeBlock("text", "~/.hermes/uploads/aight/")])])
facts = EntityList([{left: "Poort", right: "8645"}, {left: "Maximaal", right: "250 MB"}, {left: "Bewaard", right: "30 dagen"}], "default", {left: "Instelling", right: "Waarde"})
tags = TagBlock(["python3", "systemd", "tailscale"])
pick = ListBlock([ListItem("Stap 1 uitleggen", "Wat doet het script precies?", null, "Vraag", {type: "continue_conversation", context: "Leg stap 1 uit"}), ListItem("Ik gebruik Docker", null, null, null, {type: "continue_conversation"})])
\`\`\``;

// Replies a model wrote from aight's OpenUI instructions (samples/), for checking real-world output:
//   "supermarkt" -> table and stacked bar, "backup" -> markdown steps and a radio form,
//   "energieverbruik" -> donut chart and entity list
const sample = (name) => readFileSync(new URL(`./samples/${name}.md`, import.meta.url), "utf8");

/** An OpenUI reply for [question], or null to fall back to the plain markdown answer. */
export function openUiReply(question, instructions) {
  if (!instructions || !String(instructions).includes("openui-lang")) return null;
  if (/supermarkt/i.test(question)) return sample("supermarkets");
  if (/backup/i.test(question)) return sample("backups");
  if (/energieverbruik/i.test(question)) return sample("energy");
  if (/formulier|\bform\b/i.test(question)) return form;
  if (/grafiek|chart|verbruik/i.test(question)) return chart;
  if (/stappen|steps|instellen|setup/i.test(question)) return steps;
  if (/vergelijk|compare|goedkoop|contract/i.test(question)) return compare;
  return null;
}

/** The mock's answer to a turn that carries Hermes-style file notes: it names each file it got. */
export function fileReply(question) {
  const files = [...question.matchAll(/\[The user sent (?:a document|a text document|an audio file attachment|a video attachment): '(.*?)'\. It is saved at: (.+?)\. Its/g)];
  if (!files.length) return null;
  return `Ik heb ${files.length === 1 ? "je bestand" : `${files.length} bestanden`} ontvangen:\n\n` +
    files.map(([, name, path]) => `- **${name}** op \`${path}\``).join("\n") +
    `\n\nIn de echte Hermes lees ik ${files.length === 1 ? "het" : "ze"} nu met mijn eigen tools.`;
}
