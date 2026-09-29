import { readFileSync } from "node:fs";

// Scripted OpenUI replies for the mock server. They only come back when the app sent dudan's
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
s1 = StepsItem("Kopieer het script", "Zet \`dudan_upload.py\` in \`~/.hermes/\`.")
s2 = StepsItem("Start de service", "\`systemctl --user enable --now dudan-upload\`")
s3 = StepsItem("Controleer", "Tik in dudan op **Uploadservice controleren**.")
details = Accordion([AccordionItem("why", "Waarom een aparte service?", [TextContent("Hermes neemt alleen afbeeldingen aan en maximaal 10 MB per verzoek.")]), AccordionItem("where", "Waar komen de bestanden?", [CodeBlock("text", "~/.hermes/uploads/dudan/")])])
facts = EntityList([{left: "Poort", right: "8645"}, {left: "Maximaal", right: "250 MB"}, {left: "Bewaard", right: "30 dagen"}], "default", {left: "Instelling", right: "Waarde"})
tags = TagBlock(["python3", "systemd", "tailscale"])
pick = ListBlock([ListItem("Stap 1 uitleggen", "Wat doet het script precies?", null, "Vraag", {type: "continue_conversation", context: "Leg stap 1 uit"}), ListItem("Ik gebruik Docker", null, null, null, {type: "continue_conversation"})])
\`\`\``;

// The same four replies in English, for MOCK_LANG=en.
const english = {
  compare: `Here are the three cheapest fixed contracts for your usage.

\`\`\`openui-lang
root = Card([header, table, chart, note, followUps], sources)
header = CardHeader("Energy contracts", "Fixed, 1 year, 2,900 kWh")
table = Table([Col("Supplier", suppliers), Col("Per month", monthly, "number"), Col("Green", green)])
suppliers = ["Supplier A", "Supplier B", "Supplier C"]
monthly = ["€142", "€148", "€156"]
green = @Each(["yes", "partly", "no"], "g", Tag(g, null, "sm", g == "yes" ? "success" : "neutral"))
chart = BarChart(suppliers, [Series("€ per month", [142, 148, 156]), Series("€ last year", [151, 149, 170])], "grouped")
note = Callout("info", "Leaving early", "All three charge €125 if you cancel within the year [1].")
followUps = FollowUpBlock([f1, f2, f3])
f1 = FollowUpItem("Which one is greenest?")
f2 = FollowUpItem("Switch me to Supplier A")
f3 = FollowUpItem("What would a dynamic contract cost?")
sources = [{title: "Early exit fees 2026", sourceName: "ACM", url: "https://www.acm.nl"}]
\`\`\`

Supplier A is the cheapest and saves you €168 a year.`,

  form: `Fill this in and I'll book it.

\`\`\`openui-lang
root = Card([header, booking])
header = CardHeader("Book a table", "Restaurant De Kas, Amsterdam")
booking = Form("booking", buttons, [dateField, peopleField, timeField, wishes, email, extras])
dateField = FormControl("Date", DatePicker("date"))
peopleField = FormControl("Guests", Select("people", [SelectItem("2", "2 guests"), SelectItem("4", "4 guests"), SelectItem("6", "6 guests")], "How many?", {required: true}))
timeField = FormControl("Time", Chips("time", "single", [ChipItem("18", "6:00 pm"), ChipItem("19", "7:00 pm"), ChipItem("20", "8:30 pm")]))
wishes = FormControl("Requests", TextArea("wishes", "Window table, allergies…", 3), "Optional")
email = FormControl("Email", Input("email", "you@example.com", "email", {required: true, email: true}))
extras = FormControl("Extras", SwitchGroup("extras", [SwitchItem("Aperitif on arrival", null, "aperitif"), SwitchItem("Birthday", "We'll put a candle on the dessert", "birthday", true)]))
buttons = Buttons([Button("Book", Action([@ToAssistant("Book this table")]), "primary"), Button("Never mind", Action([@ToAssistant("Never mind")]), "secondary")])
\`\`\``,

  chart: `Here's how your energy use developed this year.

\`\`\`openui-lang
root = Card([header, tabs])
header = CardHeader("Energy use 2026", "Electricity and gas per month")
tabs = Tabs([t1, t2, t3])
t1 = TabItem("line", "Trend", [line])
t2 = TabItem("area", "Cumulative", [area])
t3 = TabItem("share", "Breakdown", [pie, stacked])
months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"]
line = LineChart(months, [Series("Electricity (kWh)", [320, 290, 260, 230, 200, 190, 185, 195, 240]), Series("Gas (m³)", [210, 190, 150, 90, 40, 20, 15, 18, 60])], "natural")
area = AreaChart(months, [Series("Electricity (kWh)", [320, 610, 870, 1100, 1300, 1490, 1675, 1870, 2110])])
pie = PieChart(["Heating", "Hot water", "Cooking", "Appliances"], [52, 18, 6, 24], "donut")
stacked = SingleStackedBarChart(["Solar", "Wind", "Gas", "Coal"], [34, 41, 20, 5])
\`\`\``,

  steps: `Here's how to set up the upload service.

\`\`\`openui-lang
root = Card([header, how, details, facts, tags, pick])
header = CardHeader("Set up uploads", "On the Hermes host")
how = Steps([s1, s2, s3])
s1 = StepsItem("Copy the script", "Put \`dudan_upload.py\` in \`~/.hermes/\`.")
s2 = StepsItem("Start the service", "\`systemctl --user enable --now dudan-upload\`")
s3 = StepsItem("Check it", "In dudan, tap **Check the upload service**.")
details = Accordion([AccordionItem("why", "Why a separate service?", [TextContent("Hermes only accepts images, and at most 10 MB per request.")]), AccordionItem("where", "Where do the files go?", [CodeBlock("text", "~/.hermes/uploads/dudan/")])])
facts = EntityList([{left: "Port", right: "8645"}, {left: "Largest file", right: "250 MB"}, {left: "Kept for", right: "30 days"}], "default", {left: "Setting", right: "Value"})
tags = TagBlock(["python3", "systemd", "tailscale"])
pick = ListBlock([ListItem("Explain step 1", "What does the script do?", null, "Ask", {type: "continue_conversation", context: "Explain step 1"}), ListItem("I use Docker", null, null, null, {type: "continue_conversation"})])
\`\`\``,
};

// Replies a model wrote from dudan's OpenUI instructions (samples/), for checking real-world output:
//   "supermarkt" -> table and stacked bar, "backup" -> markdown steps and a radio form,
//   "energieverbruik" -> donut chart and entity list, "offerte" -> the real Hermes reply to an
//   attached PDF quote (table and entity list)
const sample = (name) => readFileSync(new URL(`./samples/${name}.md`, import.meta.url), "utf8");

/** An OpenUI reply for [question] in [lang] ("nl" or "en"), or null to fall back to the plain markdown answer. */
export function openUiReply(question, instructions, lang = "nl") {
  if (!instructions || !String(instructions).includes("openui-lang")) return null;
  const replies = lang === "en" ? english : { compare, form, chart, steps };
  if (/supermarkt/i.test(question)) return sample("supermarkets");
  if (/backup/i.test(question)) return sample("backups");
  if (/energieverbruik/i.test(question)) return sample("energy");
  if (/offerte/i.test(question)) return sample("hermes-quote");
  if (/formulier|\bform\b/i.test(question)) return replies.form;
  if (/grafiek|chart|verbruik/i.test(question)) return replies.chart;
  if (/stappen|steps|instellen|setup/i.test(question)) return replies.steps;
  if (/vergelijk|compare|goedkoop|cheap|contract/i.test(question)) return replies.compare;
  return null;
}

/** The mock's answer to a turn that carries Hermes-style file notes: it names each file it got. */
export function fileReply(question, lang = "nl") {
  const files = [...question.matchAll(/\[The user sent (?:a document|a text document|an audio file attachment|a video attachment): '(.*?)'\. It is saved at: (.+?)\. Its/g)];
  if (!files.length) return null;
  if (lang === "en") {
    // Names only: the paths point into the mock's temp folder on the development machine.
    const one = files.length === 1;
    return `Got ${one ? "it" : `all ${files.length}`}. ${one ? "It's" : "They're"} on the Hermes host now, where I can open ${one ? "it" : "them"} with my own tools:\n\n` +
      files.map(([, name]) => `- **${name}**`).join("\n") +
      `\n\nWhat should I start with?`;
  }
  return `Ik heb ${files.length === 1 ? "je bestand" : `${files.length} bestanden`} ontvangen:\n\n` +
    files.map(([, name, path]) => `- **${name}** op \`${path}\``).join("\n") +
    `\n\nIn de echte Hermes lees ik ${files.length === 1 ? "het" : "ze"} nu met mijn eigen tools.`;
}
