import {
  DocumentService,
  ProjectService
} from "./chunk-B7Q533N4.js";
import {
  CommonModule,
  Component,
  HttpClient,
  Injectable,
  Router,
  computed,
  inject,
  setClassMetadata,
  signal,
  ɵsetClassDebugInfo,
  ɵɵadvance,
  ɵɵclassMap,
  ɵɵconditional,
  ɵɵconditionalCreate,
  ɵɵdefineComponent,
  ɵɵdefineInjectable,
  ɵɵdomElement,
  ɵɵdomElementEnd,
  ɵɵdomElementStart,
  ɵɵdomListener,
  ɵɵdomProperty,
  ɵɵgetCurrentView,
  ɵɵnextContext,
  ɵɵrepeater,
  ɵɵrepeaterCreate,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵsanitizeHtml,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵtextInterpolate2,
  ɵɵtextInterpolate3
} from "./chunk-Y4NC365O.js";

// src/app/core/services/compare.service.ts
var CompareService = class _CompareService {
  http = inject(HttpClient);
  compare(req) {
    return this.http.post("/api/compare", req);
  }
  getAiSummary(prompt) {
    return this.http.post("/api/ai/messages/stream", {
      model: "claude-sonnet-4-20250514",
      max_tokens: 1500,
      stream: true,
      messages: [{ role: "user", content: prompt }]
    }, { responseType: "text", observe: "response" });
  }
  static \u0275fac = function CompareService_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _CompareService)();
  };
  static \u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _CompareService, factory: _CompareService.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(CompareService, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], null, null);
})();

// src/app/features/compare/compare.component.ts
var _forTrack0 = ($index, $item) => $item.category;
var _forTrack1 = ($index, $item) => $item.change;
var _forTrack2 = ($index, $item) => $item.id;
function CompareComponent_Conditional_19_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 12);
    \u0275\u0275text(1);
    \u0275\u0275domElementEnd();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275textInterpolate2("", ctx_r0.doc1().fileName, " ", ctx_r0.doc1().revision ? "\xB7 Rev " + ctx_r0.doc1().revision : "");
  }
}
function CompareComponent_Conditional_27_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 12);
    \u0275\u0275text(1);
    \u0275\u0275domElementEnd();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275textInterpolate2("", ctx_r0.doc2().fileName, " ", ctx_r0.doc2().revision ? "\xB7 Rev " + ctx_r0.doc2().revision : "");
  }
}
function CompareComponent_Conditional_30_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 16)(1, "div", 26);
    \u0275\u0275text(2, "\u{1F50D}");
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 27);
    \u0275\u0275text(4, "Select two documents to compare");
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(5, "div", 28);
    \u0275\u0275text(6, "Supports DXF \xB7 DWG \xB7 IFC \xB7 PDF \xB7 Office \xB7 Images");
    \u0275\u0275domElementEnd()();
  }
}
function CompareComponent_Conditional_31_Conditional_8_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 32);
    \u0275\u0275text(1);
    \u0275\u0275domElementEnd();
  }
  if (rf & 2) {
    const r_r2 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" \u26A0\uFE0F ", r_r2.warning, " ");
  }
}
function CompareComponent_Conditional_31_Conditional_9_For_17_For_5_Conditional_8_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 12);
    \u0275\u0275text(1);
    \u0275\u0275domElementEnd();
  }
  if (rf & 2) {
    const c_r3 = \u0275\u0275nextContext().$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(c_r3.detail);
  }
}
function CompareComponent_Conditional_31_Conditional_9_For_17_For_5_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 42)(1, "span", 43);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 44)(4, "div", 45);
    \u0275\u0275text(5);
    \u0275\u0275domElementStart(6, "span", 46);
    \u0275\u0275text(7);
    \u0275\u0275domElementEnd()();
    \u0275\u0275conditionalCreate(8, CompareComponent_Conditional_31_Conditional_9_For_17_For_5_Conditional_8_Template, 2, 1, "div", 12);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const c_r3 = ctx.$implicit;
    \u0275\u0275classMap(c_r3.type === "added" ? "bg-green-50 border-l-2 border-green-500" : c_r3.type === "removed" ? "bg-red-50 border-l-2 border-red-500" : "bg-amber-50 border-l-2 border-amber-500");
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(c_r3.icon);
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate1(" ", c_r3.change, " ");
    \u0275\u0275advance();
    \u0275\u0275classMap(c_r3.severity === "high" ? "text-red-600 bg-red-100" : c_r3.severity === "medium" ? "text-amber-600 bg-amber-100" : "text-gray-500 bg-gray-100");
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", c_r3.severity, " ");
    \u0275\u0275advance();
    \u0275\u0275conditional(c_r3.detail ? 8 : -1);
  }
}
function CompareComponent_Conditional_31_Conditional_9_For_17_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 38)(1, "div", 39);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 40);
    \u0275\u0275repeaterCreate(4, CompareComponent_Conditional_31_Conditional_9_For_17_For_5_Template, 9, 8, "div", 41, _forTrack1);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const group_r4 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1(" ", group_r4.category, " ");
    \u0275\u0275advance(2);
    \u0275\u0275repeater(group_r4.items);
  }
}
function CompareComponent_Conditional_31_Conditional_9_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 33)(1, "div", 34)(2, "div", 35);
    \u0275\u0275text(3);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(4, "div", 31);
    \u0275\u0275text(5, "Total");
    \u0275\u0275domElementEnd()();
    \u0275\u0275domElementStart(6, "div", 34)(7, "div", 36);
    \u0275\u0275text(8);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(9, "div", 31);
    \u0275\u0275text(10, "Added");
    \u0275\u0275domElementEnd()();
    \u0275\u0275domElementStart(11, "div", 34)(12, "div", 37);
    \u0275\u0275text(13);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(14, "div", 31);
    \u0275\u0275text(15, "Removed");
    \u0275\u0275domElementEnd()()();
    \u0275\u0275repeaterCreate(16, CompareComponent_Conditional_31_Conditional_9_For_17_Template, 6, 1, "div", 38, _forTrack0);
  }
  if (rf & 2) {
    const r_r2 = \u0275\u0275nextContext();
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate(r_r2.totalChanges);
    \u0275\u0275advance(5);
    \u0275\u0275textInterpolate1("+", r_r2.added);
    \u0275\u0275advance(5);
    \u0275\u0275textInterpolate1("-", r_r2.removed);
    \u0275\u0275advance(3);
    \u0275\u0275repeater(ctx_r0.groupedChanges());
  }
}
function CompareComponent_Conditional_31_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 29)(1, "span", 30);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div")(4, "div", 5);
    \u0275\u0275text(5);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(6, "div", 31);
    \u0275\u0275text(7);
    \u0275\u0275domElementEnd()()();
    \u0275\u0275conditionalCreate(8, CompareComponent_Conditional_31_Conditional_8_Template, 2, 1, "div", 32);
    \u0275\u0275conditionalCreate(9, CompareComponent_Conditional_31_Conditional_9_Template, 18, 3);
  }
  if (rf & 2) {
    const r_r2 = ctx;
    \u0275\u0275classMap(r_r2.overall === "identical" ? "bg-green-50 border-green-200" : r_r2.totalChanges > 5 ? "bg-red-50 border-red-200" : "bg-amber-50 border-amber-200");
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(r_r2.overall === "identical" ? "\u2705" : r_r2.totalChanges > 5 ? "\u{1F534}" : "\u{1F7E1}");
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate(r_r2.overall === "identical" ? "Files are identical" : "Changes detected");
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate3("", r_r2.doc1Name, " vs ", r_r2.doc2Name, " \xB7 ", r_r2.fileType);
    \u0275\u0275advance();
    \u0275\u0275conditional(r_r2.warning ? 8 : -1);
    \u0275\u0275advance();
    \u0275\u0275conditional(r_r2.totalChanges > 0 ? 9 : -1);
  }
}
function CompareComponent_Conditional_38_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElement(0, "div", 20);
  }
}
function CompareComponent_Conditional_40_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 22)(1, "div", 47);
    \u0275\u0275text(2, "\u{1F916}");
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 28);
    \u0275\u0275text(4, "Run a comparison then generate an AI-powered engineering review.");
    \u0275\u0275domElementEnd()();
  }
}
function CompareComponent_Conditional_41_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElement(0, "div", 23);
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275domProperty("innerHTML", ctx_r0.aiHtml(), \u0275\u0275sanitizeHtml);
  }
}
function CompareComponent_Conditional_42_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 22)(1, "div", 48);
    \u0275\u0275text(2, "\u{1F916}");
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 49);
    \u0275\u0275text(4, "Generate an AI-powered review with revision summary, impacted disciplines, review comments and RFIs.");
    \u0275\u0275domElementEnd()();
  }
}
function CompareComponent_Conditional_43_Template(rf, ctx) {
  if (rf & 1) {
    const _r5 = \u0275\u0275getCurrentView();
    \u0275\u0275domElementStart(0, "div", 24)(1, "button", 50);
    \u0275\u0275domListener("click", function CompareComponent_Conditional_43_Template_button_click_1_listener() {
      \u0275\u0275restoreView(_r5);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.generateAI());
    });
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1(" \u2728 ", ctx_r0.aiText() ? "Regenerate Summary" : "AI Summary", " ");
  }
}
function CompareComponent_Conditional_44_For_9_Template(rf, ctx) {
  if (rf & 1) {
    const _r7 = \u0275\u0275getCurrentView();
    \u0275\u0275domElementStart(0, "div", 56);
    \u0275\u0275domListener("click", function CompareComponent_Conditional_44_For_9_Template_div_click_0_listener() {
      const doc_r8 = \u0275\u0275restoreView(_r7).$implicit;
      const ctx_r0 = \u0275\u0275nextContext(2);
      return \u0275\u0275resetView(ctx_r0.selectDoc(doc_r8));
    });
    \u0275\u0275domElementStart(1, "span", 57);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "div", 58)(4, "div", 11);
    \u0275\u0275text(5);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(6, "div", 31);
    \u0275\u0275text(7);
    \u0275\u0275domElementEnd()()();
  }
  if (rf & 2) {
    const doc_r8 = ctx.$implicit;
    const ctx_r0 = \u0275\u0275nextContext(2);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(ctx_r0.docService.getFileIcon(doc_r8));
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate(doc_r8.name);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate2("", doc_r8.fileName, " ", doc_r8.revision ? "\xB7 Rev " + doc_r8.revision : "");
  }
}
function CompareComponent_Conditional_44_Template(rf, ctx) {
  if (rf & 1) {
    const _r6 = \u0275\u0275getCurrentView();
    \u0275\u0275domElementStart(0, "div", 25)(1, "div", 51)(2, "div", 52)(3, "span", 5);
    \u0275\u0275text(4);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(5, "button", 53);
    \u0275\u0275domListener("click", function CompareComponent_Conditional_44_Template_button_click_5_listener() {
      \u0275\u0275restoreView(_r6);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.showPicker.set(false));
    });
    \u0275\u0275text(6, "\u2715");
    \u0275\u0275domElementEnd()();
    \u0275\u0275domElementStart(7, "div", 54);
    \u0275\u0275repeaterCreate(8, CompareComponent_Conditional_44_For_9_Template, 8, 4, "div", 55, _forTrack2);
    \u0275\u0275domElementEnd()()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(4);
    \u0275\u0275textInterpolate1("Select document for File ", ctx_r0.pickingSlot());
    \u0275\u0275advance(4);
    \u0275\u0275repeater(ctx_r0.docs());
  }
}
var CompareComponent = class _CompareComponent {
  router = inject(Router);
  compareService = inject(CompareService);
  docService = inject(DocumentService);
  projectService = inject(ProjectService);
  doc1 = signal(null, ...ngDevMode ? [{ debugName: "doc1" }] : (
    /* istanbul ignore next */
    []
  ));
  doc2 = signal(null, ...ngDevMode ? [{ debugName: "doc2" }] : (
    /* istanbul ignore next */
    []
  ));
  result = signal(null, ...ngDevMode ? [{ debugName: "result" }] : (
    /* istanbul ignore next */
    []
  ));
  comparing = signal(false, ...ngDevMode ? [{ debugName: "comparing" }] : (
    /* istanbul ignore next */
    []
  ));
  showPicker = signal(false, ...ngDevMode ? [{ debugName: "showPicker" }] : (
    /* istanbul ignore next */
    []
  ));
  pickingSlot = signal(1, ...ngDevMode ? [{ debugName: "pickingSlot" }] : (
    /* istanbul ignore next */
    []
  ));
  aiText = signal("", ...ngDevMode ? [{ debugName: "aiText" }] : (
    /* istanbul ignore next */
    []
  ));
  aiHtml = signal("", ...ngDevMode ? [{ debugName: "aiHtml" }] : (
    /* istanbul ignore next */
    []
  ));
  aiLoading = signal(false, ...ngDevMode ? [{ debugName: "aiLoading" }] : (
    /* istanbul ignore next */
    []
  ));
  docs = this.docService.documents;
  groupedChanges = computed(() => {
    const r = this.result();
    if (!r)
      return [];
    const groups = {};
    for (const c of r.changes) {
      const cat = c.category || "OTHER";
      if (!groups[cat])
        groups[cat] = [];
      groups[cat].push(c);
    }
    return Object.entries(groups).map(([category, items]) => ({ category, items }));
  }, ...ngDevMode ? [{ debugName: "groupedChanges" }] : (
    /* istanbul ignore next */
    []
  ));
  ngOnInit() {
    const p = this.projectService.selected();
    if (p && this.docService.documents().length === 0) {
      this.docService.loadByProject(p.id).subscribe();
    }
  }
  pickFile(slot) {
    this.pickingSlot.set(slot);
    this.showPicker.set(true);
  }
  selectDoc(doc) {
    if (this.pickingSlot() === 1)
      this.doc1.set(doc);
    else
      this.doc2.set(doc);
    this.showPicker.set(false);
  }
  swapFiles() {
    const tmp = this.doc1();
    this.doc1.set(this.doc2());
    this.doc2.set(tmp);
  }
  runCompare() {
    const d1 = this.doc1(), d2 = this.doc2();
    if (!d1 || !d2)
      return;
    this.comparing.set(true);
    this.result.set(null);
    this.aiText.set("");
    this.compareService.compare({ documentId1: d1.id, documentId2: d2.id }).subscribe({
      next: (r) => {
        this.result.set(r);
        this.comparing.set(false);
      },
      error: () => this.comparing.set(false)
    });
  }
  async generateAI() {
    const r = this.result();
    if (!r)
      return;
    this.aiLoading.set(true);
    this.aiText.set("");
    this.aiHtml.set("");
    const changes = r.changes.map((c) => `\u2022 ${c.type.toUpperCase()} [${c.category}] ${c.change}${c.detail ? " \u2014 " + c.detail : ""}`).join("\n");
    const prompt = `You are a senior AEC document controller reviewing a drawing revision in a CDE.

File 1: "${r.doc1Name}"${r.doc1Revision ? " \u2014 Rev " + r.doc1Revision : ""}
File 2: "${r.doc2Name}"${r.doc2Revision ? " \u2014 Rev " + r.doc2Revision : ""}
Type: ${r.fileType} | ${r.overall} | ${r.totalChanges} changes (${r.added} added, ${r.removed} removed)

Changes:
${changes}

Produce a structured report with exactly these 5 sections:
1. REVISION SUMMARY
2. KEY CHANGES IDENTIFIED
3. IMPACTED DISCIPLINES
4. REVIEW COMMENTS
5. SUGGESTED RFIs (format: RFI-001: Subject \u2014 Question)

Under 400 words. Professional engineering language.`;
    try {
      const res = await fetch("/api/ai/messages/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": "Bearer " + this.getToken()
        },
        body: JSON.stringify({
          model: "claude-sonnet-4-20250514",
          max_tokens: 1500,
          stream: true,
          messages: [{ role: "user", content: prompt }]
        })
      });
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = "", full = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done)
          break;
        buf += dec.decode(value, { stream: true });
        const lines = buf.split("\n");
        buf = lines.pop();
        for (const line of lines) {
          if (!line.startsWith("data: "))
            continue;
          const raw = line.slice(6).trim();
          if (raw === "[DONE]")
            continue;
          try {
            const evt = JSON.parse(raw);
            if (evt.type === "content_block_delta" && evt.delta?.type === "text_delta") {
              full += evt.delta.text;
              this.aiText.set(full);
            }
          } catch {
          }
        }
      }
      this.aiHtml.set(this.formatReport(full));
    } catch (e) {
      this.aiText.set("Error: " + e.message);
    } finally {
      this.aiLoading.set(false);
    }
  }
  formatReport(text) {
    const sectionRe = /^(\d+\.\s+)(REVISION SUMMARY|KEY CHANGES IDENTIFIED|IMPACTED DISCIPLINES|REVIEW COMMENTS|SUGGESTED RFIs)/i;
    const rfiRe = /^(RFI-\d+:)/i;
    const icons = {
      "REVISION SUMMARY": "\u{1F4CB}",
      "KEY CHANGES IDENTIFIED": "\u{1F50D}",
      "IMPACTED DISCIPLINES": "\u{1F3D7}",
      "REVIEW COMMENTS": "\u270D\uFE0F",
      "SUGGESTED RFIs": "\u2753"
    };
    return text.split("\n").map((line) => {
      const t = line.trim();
      if (!t)
        return '<div style="height:5px"></div>';
      if (sectionRe.test(t)) {
        const key = Object.keys(icons).find((k) => t.toUpperCase().includes(k)) || "";
        return `<div style="display:flex;align-items:center;gap:6px;margin:12px 0 5px;padding-bottom:4px;border-bottom:1px solid #dde1e7">
          <span>${icons[key] || "\u2022"}</span>
          <strong style="font-size:.78rem;color:var(--accent);text-transform:uppercase;letter-spacing:.4px">${t}</strong>
        </div>`;
      }
      if (rfiRe.test(t)) {
        const d = t.indexOf("\u2014");
        const ref = d > 0 ? t.slice(0, d).trim() : t;
        const rest = d > 0 ? t.slice(d + 1).trim() : "";
        return `<div style="background:#fffbeb;border-left:3px solid #f59e0b;border-radius:3px;padding:6px 10px;margin:3px 0;font-size:.79rem">
          <strong style="color:#b45309">${ref}</strong>${rest ? " \u2014 " + rest : ""}
        </div>`;
      }
      if (/^[•\-\*]\s+/.test(t) || /^\d+\.\s+[a-z]/i.test(t)) {
        return `<div style="display:flex;gap:6px;margin:2px 0;font-size:.79rem">
          <span style="color:var(--accent);flex-shrink:0">\u25B8</span>
          <span>${t.replace(/^[•\-\*]\s+/, "").replace(/^\d+\.\s+/, "")}</span>
        </div>`;
      }
      return `<p style="margin:3px 0;font-size:.79rem">${t}</p>`;
    }).join("");
  }
  getToken() {
    return localStorage.getItem("cde_token") || "";
  }
  goBack() {
    this.router.navigate(["/"]);
  }
  static \u0275fac = function CompareComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _CompareComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _CompareComponent, selectors: [["app-compare"]], decls: 45, vars: 16, consts: [[1, "fixed", "inset-0", "flex", "flex-col", 2, "background", "var(--bg)", "z-index", "700"], [1, "flex", "items-center", "h-12", "px-4", "gap-3", "flex-shrink-0", "text-white", 2, "background", "var(--nav)", "box-shadow", "0 2px 4px rgba(0,0,0,.15)"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", "transition-colors", 3, "click"], [1, "flex", "items-center", "gap-2", "flex-1"], [1, "text-lg"], [1, "font-semibold", "text-sm"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", 3, "click"], [1, "text-xs", "px-4", "py-1.5", "rounded", "font-semibold", "transition-colors", "disabled:opacity-40", 2, "background", "#fff", "color", "var(--accent)", 3, "click", "disabled"], [1, "flex", "items-center", "gap-3", "px-4", "py-2", "bg-white", "border-b", "border-gray-200", "flex-shrink-0"], [1, "flex-1", "border-2", "rounded-lg", "p-3", "cursor-pointer", "transition-all", "min-w-0", 3, "click"], [1, "text-xs", "font-semibold", "uppercase", "tracking-wide", "text-gray-500", "mb-1"], [1, "font-medium", "text-sm", "truncate"], [1, "text-xs", "text-gray-500", "mt-0.5"], [1, "text-xs", "font-bold", "text-gray-500", "px-2", "py-1", "bg-gray-100", "rounded-full", "flex-shrink-0"], [1, "flex", "flex-1", "overflow-hidden", "min-h-0"], [1, "flex-1", "overflow-y-auto", "p-5", "min-w-0"], [1, "flex", "flex-col", "items-center", "justify-center", "h-full", "text-gray-400"], [1, "border-l", "border-gray-200", "bg-white", "flex", "flex-col", "flex-shrink-0", 2, "width", "600px"], [1, "flex", "items-center", "gap-2", "px-4", "py-3", "border-b", "border-gray-200", "bg-gray-50", "flex-shrink-0"], [1, "font-semibold", "text-sm", "flex-1"], [1, "w-4", "h-4", "border-2", "border-blue-200", "border-t-accent", "rounded-full", "animate-spin"], [1, "flex-1", "overflow-y-auto", "min-h-0"], [1, "flex", "flex-col", "items-center", "justify-center", "h-full", "text-gray-400", "p-6", "text-center"], [1, "p-4", "text-sm", "leading-relaxed", "text-gray-700", 3, "innerHTML"], [1, "p-3", "border-t", "border-gray-200", "flex-shrink-0"], [1, "fixed", "inset-0", "bg-black/60", "backdrop-blur-sm", "z-[800]", "flex", "items-center", "justify-center"], [1, "text-5xl", "mb-4"], [1, "font-semibold", "mb-1"], [1, "text-sm"], [1, "flex", "items-center", "gap-3", "p-3", "rounded-lg", "mb-4", "border"], [1, "text-2xl"], [1, "text-xs", "text-gray-500"], [1, "p-3", "bg-amber-50", "border", "border-amber-200", "rounded-lg", "mb-4", "text-xs", "text-amber-800", "whitespace-pre-wrap"], [1, "flex", "gap-3", "mb-4", "flex-wrap"], [1, "bg-white", "rounded", "border", "border-gray-200", "px-4", "py-2", "text-center", "min-w-16"], [1, "text-xl", "font-bold", "font-mono", "text-accent"], [1, "text-xl", "font-bold", "font-mono", "text-green-600"], [1, "text-xl", "font-bold", "font-mono", "text-red-600"], [1, "mb-4"], [1, "text-xs", "font-semibold", "uppercase", "tracking-wider", "text-gray-500", "mb-2"], [1, "space-y-1.5"], [1, "flex", "items-start", "gap-3", "p-2.5", "rounded-md", "text-sm", 3, "class"], [1, "flex", "items-start", "gap-3", "p-2.5", "rounded-md", "text-sm"], [1, "text-base", "flex-shrink-0"], [1, "flex-1", "min-w-0"], [1, "font-medium", "text-gray-800"], [1, "ml-1.5", "text-xs", "px-1.5", "py-0.5", "rounded-full", "font-semibold"], [1, "text-4xl", "mb-3"], [1, "text-3xl", "mb-3"], [1, "text-sm", "mb-4"], [1, "w-full", "flex", "items-center", "justify-center", "gap-2", "py-2", "rounded", "border", "border-blue-200", "bg-blue-50", "text-accent", "text-sm", "font-medium", "hover:bg-blue-100", "transition-colors", 3, "click"], [1, "bg-white", "rounded-lg", "shadow-2xl", "w-96", "max-h-[70vh]", "flex", "flex-col"], [1, "flex", "items-center", "justify-between", "p-4", "border-b", "border-gray-200"], [1, "text-gray-400", "hover:text-gray-600", 3, "click"], [1, "overflow-y-auto", "flex-1", "p-2"], [1, "flex", "items-center", "gap-3", "p-2.5", "rounded-md", "cursor-pointer", "hover:bg-gray-50", "transition-colors"], [1, "flex", "items-center", "gap-3", "p-2.5", "rounded-md", "cursor-pointer", "hover:bg-gray-50", "transition-colors", 3, "click"], [1, "text-xl", "flex-shrink-0"], [1, "min-w-0", "flex-1"]], template: function CompareComponent_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275domElementStart(0, "div", 0)(1, "div", 1)(2, "button", 2);
      \u0275\u0275domListener("click", function CompareComponent_Template_button_click_2_listener() {
        return ctx.goBack();
      });
      \u0275\u0275text(3, " \u2190 Back ");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(4, "div", 3)(5, "span", 4);
      \u0275\u0275text(6, "\u{1F50D}");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(7, "span", 5);
      \u0275\u0275text(8, "Compare Documents");
      \u0275\u0275domElementEnd()();
      \u0275\u0275domElementStart(9, "button", 6);
      \u0275\u0275domListener("click", function CompareComponent_Template_button_click_9_listener() {
        return ctx.swapFiles();
      });
      \u0275\u0275text(10, "\u21C4 Swap");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(11, "button", 7);
      \u0275\u0275domListener("click", function CompareComponent_Template_button_click_11_listener() {
        return ctx.runCompare();
      });
      \u0275\u0275text(12);
      \u0275\u0275domElementEnd()();
      \u0275\u0275domElementStart(13, "div", 8)(14, "div", 9);
      \u0275\u0275domListener("click", function CompareComponent_Template_div_click_14_listener() {
        return ctx.pickFile(1);
      });
      \u0275\u0275domElementStart(15, "div", 10);
      \u0275\u0275text(16, "\u{1F4C4} File 1 \u2014 Original");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(17, "div", 11);
      \u0275\u0275text(18);
      \u0275\u0275domElementEnd();
      \u0275\u0275conditionalCreate(19, CompareComponent_Conditional_19_Template, 2, 2, "div", 12);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(20, "div", 13);
      \u0275\u0275text(21, "VS");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(22, "div", 9);
      \u0275\u0275domListener("click", function CompareComponent_Template_div_click_22_listener() {
        return ctx.pickFile(2);
      });
      \u0275\u0275domElementStart(23, "div", 10);
      \u0275\u0275text(24, "\u{1F4C4} File 2 \u2014 Revised");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(25, "div", 11);
      \u0275\u0275text(26);
      \u0275\u0275domElementEnd();
      \u0275\u0275conditionalCreate(27, CompareComponent_Conditional_27_Template, 2, 2, "div", 12);
      \u0275\u0275domElementEnd()();
      \u0275\u0275domElementStart(28, "div", 14)(29, "div", 15);
      \u0275\u0275conditionalCreate(30, CompareComponent_Conditional_30_Template, 7, 0, "div", 16);
      \u0275\u0275conditionalCreate(31, CompareComponent_Conditional_31_Template, 10, 9);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(32, "div", 17)(33, "div", 18)(34, "span");
      \u0275\u0275text(35, "\u2728");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(36, "span", 19);
      \u0275\u0275text(37, "AI Summary");
      \u0275\u0275domElementEnd();
      \u0275\u0275conditionalCreate(38, CompareComponent_Conditional_38_Template, 1, 0, "div", 20);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(39, "div", 21);
      \u0275\u0275conditionalCreate(40, CompareComponent_Conditional_40_Template, 5, 0, "div", 22)(41, CompareComponent_Conditional_41_Template, 1, 1, "div", 23)(42, CompareComponent_Conditional_42_Template, 5, 0, "div", 22);
      \u0275\u0275domElementEnd();
      \u0275\u0275conditionalCreate(43, CompareComponent_Conditional_43_Template, 3, 1, "div", 24);
      \u0275\u0275domElementEnd()()();
      \u0275\u0275conditionalCreate(44, CompareComponent_Conditional_44_Template, 10, 1, "div", 25);
    }
    if (rf & 2) {
      let tmp_3_0;
      let tmp_6_0;
      let tmp_9_0;
      \u0275\u0275advance(11);
      \u0275\u0275domProperty("disabled", !ctx.doc1() || !ctx.doc2() || ctx.comparing());
      \u0275\u0275advance();
      \u0275\u0275textInterpolate1(" ", ctx.comparing() ? "\u23F3 Analysing..." : "\u{1F50D} Compare", " ");
      \u0275\u0275advance(2);
      \u0275\u0275classMap(ctx.doc1() ? "border-accent bg-blue-50" : "border-dashed border-gray-300 hover:border-accent");
      \u0275\u0275advance(4);
      \u0275\u0275textInterpolate(((tmp_3_0 = ctx.doc1()) == null ? null : tmp_3_0.name) || "Click to select");
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.doc1() ? 19 : -1);
      \u0275\u0275advance(3);
      \u0275\u0275classMap(ctx.doc2() ? "border-accent bg-blue-50" : "border-dashed border-gray-300 hover:border-accent");
      \u0275\u0275advance(4);
      \u0275\u0275textInterpolate(((tmp_6_0 = ctx.doc2()) == null ? null : tmp_6_0.name) || "Click to select");
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.doc2() ? 27 : -1);
      \u0275\u0275advance(3);
      \u0275\u0275conditional(!ctx.result() ? 30 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional((tmp_9_0 = ctx.result()) ? 31 : -1, tmp_9_0);
      \u0275\u0275advance(7);
      \u0275\u0275conditional(ctx.aiLoading() ? 38 : -1);
      \u0275\u0275advance(2);
      \u0275\u0275conditional(!ctx.result() ? 40 : ctx.aiText() ? 41 : 42);
      \u0275\u0275advance(3);
      \u0275\u0275conditional(ctx.result() && !ctx.aiLoading() ? 43 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.showPicker() ? 44 : -1);
    }
  }, dependencies: [CommonModule], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(CompareComponent, [{
    type: Component,
    args: [{
      selector: "app-compare",
      standalone: true,
      imports: [CommonModule],
      template: `
    <div class="fixed inset-0 flex flex-col" style="background:var(--bg);z-index:700">

      <!-- Top bar -->
      <div class="flex items-center h-12 px-4 gap-3 flex-shrink-0 text-white"
           style="background:var(--nav);box-shadow:0 2px 4px rgba(0,0,0,.15)">
        <button (click)="goBack()"
          class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20 transition-colors">
          \u2190 Back
        </button>
        <div class="flex items-center gap-2 flex-1">
          <span class="text-lg">\u{1F50D}</span>
          <span class="font-semibold text-sm">Compare Documents</span>
        </div>
        <button (click)="swapFiles()"
          class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u21C4 Swap</button>
        <button (click)="runCompare()" [disabled]="!doc1() || !doc2() || comparing()"
          class="text-xs px-4 py-1.5 rounded font-semibold transition-colors disabled:opacity-40"
          style="background:#fff;color:var(--accent)">
          {{ comparing() ? '\u23F3 Analysing...' : '\u{1F50D} Compare' }}
        </button>
      </div>

      <!-- File selector bar -->
      <div class="flex items-center gap-3 px-4 py-2 bg-white border-b border-gray-200 flex-shrink-0">
        <div (click)="pickFile(1)"
          class="flex-1 border-2 rounded-lg p-3 cursor-pointer transition-all min-w-0"
          [class]="doc1() ? 'border-accent bg-blue-50' : 'border-dashed border-gray-300 hover:border-accent'">
          <div class="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1">\u{1F4C4} File 1 \u2014 Original</div>
          <div class="font-medium text-sm truncate">{{ doc1()?.name || 'Click to select' }}</div>
          @if (doc1()) {
            <div class="text-xs text-gray-500 mt-0.5">{{ doc1()!.fileName }} {{ doc1()!.revision ? '\xB7 Rev ' + doc1()!.revision : '' }}</div>
          }
        </div>

        <div class="text-xs font-bold text-gray-500 px-2 py-1 bg-gray-100 rounded-full flex-shrink-0">VS</div>

        <div (click)="pickFile(2)"
          class="flex-1 border-2 rounded-lg p-3 cursor-pointer transition-all min-w-0"
          [class]="doc2() ? 'border-accent bg-blue-50' : 'border-dashed border-gray-300 hover:border-accent'">
          <div class="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1">\u{1F4C4} File 2 \u2014 Revised</div>
          <div class="font-medium text-sm truncate">{{ doc2()?.name || 'Click to select' }}</div>
          @if (doc2()) {
            <div class="text-xs text-gray-500 mt-0.5">{{ doc2()!.fileName }} {{ doc2()!.revision ? '\xB7 Rev ' + doc2()!.revision : '' }}</div>
          }
        </div>
      </div>

      <!-- Body: change list + AI sidebar -->
      <div class="flex flex-1 overflow-hidden min-h-0">

        <!-- Change list -->
        <div class="flex-1 overflow-y-auto p-5 min-w-0">
          @if (!result()) {
            <div class="flex flex-col items-center justify-center h-full text-gray-400">
              <div class="text-5xl mb-4">\u{1F50D}</div>
              <div class="font-semibold mb-1">Select two documents to compare</div>
              <div class="text-sm">Supports DXF \xB7 DWG \xB7 IFC \xB7 PDF \xB7 Office \xB7 Images</div>
            </div>
          }

          @if (result(); as r) {
            <!-- Overall banner -->
            <div class="flex items-center gap-3 p-3 rounded-lg mb-4 border"
              [class]="r.overall === 'identical'
                ? 'bg-green-50 border-green-200'
                : r.totalChanges > 5
                  ? 'bg-red-50 border-red-200'
                  : 'bg-amber-50 border-amber-200'">
              <span class="text-2xl">{{ r.overall === 'identical' ? '\u2705' : r.totalChanges > 5 ? '\u{1F534}' : '\u{1F7E1}' }}</span>
              <div>
                <div class="font-semibold text-sm">{{ r.overall === 'identical' ? 'Files are identical' : 'Changes detected' }}</div>
                <div class="text-xs text-gray-500">{{ r.doc1Name }} vs {{ r.doc2Name }} \xB7 {{ r.fileType }}</div>
              </div>
            </div>

            <!-- Warning -->
            @if (r.warning) {
              <div class="p-3 bg-amber-50 border border-amber-200 rounded-lg mb-4 text-xs text-amber-800 whitespace-pre-wrap">
                \u26A0\uFE0F {{ r.warning }}
              </div>
            }

            <!-- Stats -->
            @if (r.totalChanges > 0) {
              <div class="flex gap-3 mb-4 flex-wrap">
                <div class="bg-white rounded border border-gray-200 px-4 py-2 text-center min-w-16">
                  <div class="text-xl font-bold font-mono text-accent">{{ r.totalChanges }}</div>
                  <div class="text-xs text-gray-500">Total</div>
                </div>
                <div class="bg-white rounded border border-gray-200 px-4 py-2 text-center min-w-16">
                  <div class="text-xl font-bold font-mono text-green-600">+{{ r.added }}</div>
                  <div class="text-xs text-gray-500">Added</div>
                </div>
                <div class="bg-white rounded border border-gray-200 px-4 py-2 text-center min-w-16">
                  <div class="text-xl font-bold font-mono text-red-600">-{{ r.removed }}</div>
                  <div class="text-xs text-gray-500">Removed</div>
                </div>
              </div>

              <!-- Changes by category -->
              @for (group of groupedChanges(); track group.category) {
                <div class="mb-4">
                  <div class="text-xs font-semibold uppercase tracking-wider text-gray-500 mb-2">
                    {{ group.category }}
                  </div>
                  <div class="space-y-1.5">
                    @for (c of group.items; track c.change) {
                      <div class="flex items-start gap-3 p-2.5 rounded-md text-sm"
                        [class]="c.type === 'added'
                          ? 'bg-green-50 border-l-2 border-green-500'
                          : c.type === 'removed'
                            ? 'bg-red-50 border-l-2 border-red-500'
                            : 'bg-amber-50 border-l-2 border-amber-500'">
                        <span class="text-base flex-shrink-0">{{ c.icon }}</span>
                        <div class="flex-1 min-w-0">
                          <div class="font-medium text-gray-800">
                            {{ c.change }}
                            <span class="ml-1.5 text-xs px-1.5 py-0.5 rounded-full font-semibold"
                              [class]="c.severity === 'high' ? 'text-red-600 bg-red-100'
                                     : c.severity === 'medium' ? 'text-amber-600 bg-amber-100'
                                     : 'text-gray-500 bg-gray-100'">
                              {{ c.severity }}
                            </span>
                          </div>
                          @if (c.detail) {
                            <div class="text-xs text-gray-500 mt-0.5">{{ c.detail }}</div>
                          }
                        </div>
                      </div>
                    }
                  </div>
                </div>
              }
            }
          }
        </div>

        <!-- AI Sidebar (600px) -->
        <div class="border-l border-gray-200 bg-white flex flex-col flex-shrink-0" style="width:600px">
          <div class="flex items-center gap-2 px-4 py-3 border-b border-gray-200 bg-gray-50 flex-shrink-0">
            <span>\u2728</span>
            <span class="font-semibold text-sm flex-1">AI Summary</span>
            @if (aiLoading()) {
              <div class="w-4 h-4 border-2 border-blue-200 border-t-accent rounded-full animate-spin"></div>
            }
          </div>

          <div class="flex-1 overflow-y-auto min-h-0">
            @if (!result()) {
              <div class="flex flex-col items-center justify-center h-full text-gray-400 p-6 text-center">
                <div class="text-4xl mb-3">\u{1F916}</div>
                <div class="text-sm">Run a comparison then generate an AI-powered engineering review.</div>
              </div>
            } @else if (aiText()) {
              <div class="p-4 text-sm leading-relaxed text-gray-700" [innerHTML]="aiHtml()"></div>
            } @else {
              <div class="flex flex-col items-center justify-center h-full text-gray-400 p-6 text-center">
                <div class="text-3xl mb-3">\u{1F916}</div>
                <div class="text-sm mb-4">Generate an AI-powered review with revision summary, impacted disciplines, review comments and RFIs.</div>
              </div>
            }
          </div>

          @if (result() && !aiLoading()) {
            <div class="p-3 border-t border-gray-200 flex-shrink-0">
              <button (click)="generateAI()"
                class="w-full flex items-center justify-center gap-2 py-2 rounded border border-blue-200 bg-blue-50 text-accent text-sm font-medium hover:bg-blue-100 transition-colors">
                \u2728 {{ aiText() ? 'Regenerate Summary' : 'AI Summary' }}
              </button>
            </div>
          }
        </div>
      </div>
    </div>

    <!-- Document picker modal -->
    @if (showPicker()) {
      <div class="fixed inset-0 bg-black/60 backdrop-blur-sm z-[800] flex items-center justify-center">
        <div class="bg-white rounded-lg shadow-2xl w-96 max-h-[70vh] flex flex-col">
          <div class="flex items-center justify-between p-4 border-b border-gray-200">
            <span class="font-semibold text-sm">Select document for File {{ pickingSlot() }}</span>
            <button (click)="showPicker.set(false)" class="text-gray-400 hover:text-gray-600">\u2715</button>
          </div>
          <div class="overflow-y-auto flex-1 p-2">
            @for (doc of docs(); track doc.id) {
              <div (click)="selectDoc(doc)"
                class="flex items-center gap-3 p-2.5 rounded-md cursor-pointer hover:bg-gray-50 transition-colors">
                <span class="text-xl flex-shrink-0">{{ docService.getFileIcon(doc) }}</span>
                <div class="min-w-0 flex-1">
                  <div class="font-medium text-sm truncate">{{ doc.name }}</div>
                  <div class="text-xs text-gray-500">{{ doc.fileName }} {{ doc.revision ? '\xB7 Rev ' + doc.revision : '' }}</div>
                </div>
              </div>
            }
          </div>
        </div>
      </div>
    }
  `
    }]
  }], null, null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(CompareComponent, { className: "CompareComponent", filePath: "src/app/features/compare/compare.component.ts", lineNumber: 214 });
})();
export {
  CompareComponent
};
//# sourceMappingURL=chunk-4VNFMOHA.js.map
