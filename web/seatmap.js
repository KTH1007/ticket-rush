import { request } from './api.js';

const CELL = 4;
const SECTION_GAP = 24;
const LABEL_HEIGHT = 14;
const MAX_SELECTION = 2;
const AVAILABLE = 0;
const STATUS_NAMES = ['선택 가능', '홀드됨', '판매됨'];
const GRADE_COLORS = ['#e0b43c', '#4f8ad9', '#58b368', '#a07acc', '#d97a4f'];
const COLOR_HELD = '#f08a24';
const COLOR_SOLD = '#9aa5b1';
const COLOR_SELECTED = '#d32f2f';
const COLOR_MINE = '#6a1b9a';

export class SeatMap {
  constructor(canvas, handlers) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.handlers = handlers;
    this.ratio = window.devicePixelRatio || 1;
    this.loadSeq = 0;
    this.reset();
    canvas.addEventListener('click', (event) => this.onClick(event));
    canvas.addEventListener('mousemove', (event) => this.handlers.onHover(this.describeAt(event)));
  }

  reset() {
    this.seats = [];
    this.sections = [];
    this.cells = new Map();
    this.gradeNames = new Map();
    this.gradeColors = new Map();
    this.x = new Int32Array(0);
    this.y = new Int32Array(0);
    this.status = new Uint8Array(0);
    this.selected = new Set();
    this.mine = new Set();
    this.builtAt = null;
    this.width = 1;
    this.height = 1;
  }

  async load(eventId) {
    const seq = ++this.loadSeq;
    this.reset();
    const response = await request('GET', `/api/events/${eventId}/seats/layout`);
    if (seq !== this.loadSeq || !response.ok) return response;

    const { seatCount, grades, seats } = response.data;
    this.seats = new Array(seatCount);
    this.x = new Int32Array(seatCount);
    this.y = new Int32Array(seatCount);
    this.status = new Uint8Array(seatCount);
    grades.forEach((grade, index) => {
      this.gradeNames.set(grade.id, grade.name);
      this.gradeColors.set(grade.id, GRADE_COLORS[index % GRADE_COLORS.length]);
    });

    const bySection = new Map();
    for (const seat of seats) {
      let section = bySection.get(seat.section);
      if (!section) {
        section = { name: seat.section, index: this.sections.length, cols: 0, rows: new Map(), x0: 0 };
        bySection.set(seat.section, section);
        this.sections.push(section);
      }
      if (!section.rows.has(seat.rowLabel)) section.rows.set(seat.rowLabel, section.rows.size);
      section.cols = Math.max(section.cols, seat.seatNo);
    }

    let offset = 0;
    let maxRows = 0;
    for (const section of this.sections) {
      section.x0 = offset;
      offset += section.cols * CELL + SECTION_GAP;
      maxRows = Math.max(maxRows, section.rows.size);
    }
    this.width = Math.max(offset - SECTION_GAP, 1);
    this.height = LABEL_HEIGHT + maxRows * CELL;

    for (const seat of seats) {
      const section = bySection.get(seat.section);
      const col = seat.seatNo - 1;
      const row = section.rows.get(seat.rowLabel);
      this.seats[seat.ordinal] = seat;
      this.x[seat.ordinal] = section.x0 + col * CELL;
      this.y[seat.ordinal] = LABEL_HEIGHT + row * CELL;
      this.cells.set(this.cellKey(section.index, row, col), seat.ordinal);
    }

    this.canvas.width = Math.ceil(this.width * this.ratio);
    this.canvas.height = Math.ceil(this.height * this.ratio);
    this.canvas.style.width = `${this.width}px`;
    this.canvas.style.height = `${this.height}px`;
    this.draw();
    return response;
  }

  legend() {
    const grades = [...this.gradeNames].map(([id, name]) => ({ label: name, color: this.gradeColors.get(id) }));
    return [
      ...grades,
      { label: '홀드됨', color: COLOR_HELD },
      { label: '판매됨', color: COLOR_SOLD },
      { label: '내가 선택', color: COLOR_SELECTED },
      { label: '내가 홀드', color: COLOR_MINE },
    ];
  }

  // 상태 비트맵: 좌석 ordinal i는 바이트 i/4의 상위 비트부터 2비트(0 가능, 1 홀드, 2 판매)
  applyStatus(data, guardStale) {
    if (data.bitsPerSeat !== 2) return { error: `지원하지 않는 bitsPerSeat: ${data.bitsPerSeat}` };
    const builtAt = new Date(data.builtAt);
    if (guardStale && this.builtAt && builtAt < this.builtAt) return { stale: true, builtAt };

    this.builtAt = builtAt;
    const bytes = atob(data.bitmap);
    const counts = [0, 0, 0];
    const limit = Math.min(data.seatCount, this.status.length);
    for (let i = 0; i < limit; i += 1) {
      const value = (bytes.charCodeAt(i >> 2) >> ((3 - (i & 3)) * 2)) & 3;
      this.status[i] = value;
      if (value < counts.length) counts[value] += 1;
    }
    for (const ordinal of [...this.selected]) {
      if (this.status[ordinal] !== AVAILABLE) this.selected.delete(ordinal);
    }
    this.draw();
    this.handlers.onChange();
    return { stale: false, builtAt, counts };
  }

  selectedIds() {
    return [...this.selected].map((ordinal) => this.seats[ordinal].id);
  }

  selectedCount() {
    return this.selected.size;
  }

  describeSelection() {
    if (this.selected.size === 0) return '선택한 좌석 없음';
    return [...this.selected].map((ordinal) => this.label(ordinal)).join(', ');
  }

  autoSelect() {
    this.selected.clear();
    const total = this.seats.length;
    const start = Math.floor(Math.random() * total);
    for (let step = 0; step < total && this.selected.size < MAX_SELECTION; step += 1) {
      const ordinal = (start + step) % total;
      if (this.seats[ordinal] && this.status[ordinal] === AVAILABLE) this.selected.add(ordinal);
    }
    this.draw();
    this.handlers.onChange();
  }

  clearSelection() {
    this.selected.clear();
    this.draw();
    this.handlers.onChange();
  }

  markSelectedAsMine() {
    this.selected.forEach((ordinal) => this.mine.add(ordinal));
    this.selected.clear();
    this.draw();
    this.handlers.onChange();
  }

  cellKey(sectionIndex, row, col) {
    return (sectionIndex * 100000 + row) * 100000 + col;
  }

  locate(event) {
    const rect = this.canvas.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    if (py < LABEL_HEIGHT) return null;
    const row = Math.floor((py - LABEL_HEIGHT) / CELL);
    for (const section of this.sections) {
      const col = Math.floor((px - section.x0) / CELL);
      if (col < 0 || col >= section.cols) continue;
      const ordinal = this.cells.get(this.cellKey(section.index, row, col));
      if (ordinal !== undefined) return ordinal;
    }
    return null;
  }

  label(ordinal) {
    const seat = this.seats[ordinal];
    return `${seat.section} ${seat.rowLabel}열 ${seat.seatNo}번`;
  }

  describeAt(event) {
    const ordinal = this.locate(event);
    if (ordinal === null) return '좌석 위에 마우스를 올리면 정보가 보입니다';
    const seat = this.seats[ordinal];
    return `${this.label(ordinal)} (${this.gradeNames.get(seat.gradeId)}) ${STATUS_NAMES[this.status[ordinal]] ?? '알 수 없음'}`;
  }

  onClick(event) {
    const ordinal = this.locate(event);
    if (ordinal === null) return;
    if (this.selected.has(ordinal)) {
      this.selected.delete(ordinal);
    } else if (this.status[ordinal] !== AVAILABLE || this.mine.has(ordinal)) {
      this.handlers.onMessage(`${this.label(ordinal)}은 선택할 수 없습니다(${STATUS_NAMES[this.status[ordinal]]})`);
      return;
    } else if (this.selected.size >= MAX_SELECTION) {
      this.handlers.onMessage(`최대 ${MAX_SELECTION}석까지 선택할 수 있습니다`);
      return;
    } else {
      this.selected.add(ordinal);
    }
    this.draw();
    this.handlers.onChange();
  }

  colorOf(ordinal, seat) {
    if (this.selected.has(ordinal)) return COLOR_SELECTED;
    if (this.mine.has(ordinal)) return COLOR_MINE;
    const status = this.status[ordinal];
    if (status === 1) return COLOR_HELD;
    if (status === 2) return COLOR_SOLD;
    return this.gradeColors.get(seat.gradeId);
  }

  draw() {
    const ctx = this.ctx;
    ctx.setTransform(this.ratio, 0, 0, this.ratio, 0, 0);
    ctx.clearRect(0, 0, this.width, this.height);
    ctx.fillStyle = '#1f2933';
    ctx.font = '11px sans-serif';
    ctx.textBaseline = 'top';
    for (const section of this.sections) {
      ctx.fillText(`${section.name} (${section.cols}x${section.rows.size})`, section.x0, 0);
    }
    for (let ordinal = 0; ordinal < this.seats.length; ordinal += 1) {
      const seat = this.seats[ordinal];
      if (!seat) continue;
      ctx.fillStyle = this.colorOf(ordinal, seat);
      ctx.fillRect(this.x[ordinal], this.y[ordinal], CELL - 1, CELL - 1);
    }
  }
}
