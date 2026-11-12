# Arc

Arc คือภาษา Assembly จำลองแบบง่าย เขียนด้วย Java ใช้สำหรับเรียนรู้และทดลองแนวคิดของ Assembly เช่น register, RAM, stack, flag, jump และ call/ret โดยไม่ต้องยุ่งกับ CPU จริง

- `ArcRuntime.java` ตัว interpreter (tokenize, โหลดโปรแกรม, รันทีละคำสั่ง)
- `Main.java` CLI สำหรับรันไฟล์ Arc และอ่านค่า config
- `Json.java` ไลบรารี JSON ไฟล์เดียวที่ `Main` ใช้อ่าน `config.json`
- `Lexer` / `GrammarState` คลาสที่ `ArcRuntime` เรียกใช้ตอนสร้าง object (ต้องอยู่ใน classpath เดียวกัน)

## ความต้องการ

- Java 17 ขึ้นไป (ใช้ `String.formatted`, `StringBuilder.isEmpty`, switch แบบ `->`)

## Build และรัน

```bash
javac *.java
java Main program.arc
```

ส่งหลายไฟล์ได้ จะรันทีละไฟล์ตามลำดับ และแต่ละไฟล์เริ่มจากสถานะว่าง (register, RAM, stack ถูกรีเซ็ตทุกครั้ง)

```bash
java Main a.arc b.arc
```

### Option

| Option | ความหมาย |
|---|---|
| `-d` | เปิด debug (แสดง config, โปรแกรมที่ tokenize แล้ว, label ที่พบ, ตัวแปรที่นิยาม) |
| `--name=<ค่า>` | เขียนทับค่า `name` ใน config |

Option จาก command line จะเขียนทับค่าที่อ่านจาก `config/config.json`

### Config

`Main` อ่านไฟล์ `config/config.json` และต้องมีไฟล์นี้อยู่ ไม่เช่นนั้นจะ error ถ้าไม่ต้องการตั้งค่าอะไรให้ใส่ `{}` หรือไฟล์ว่างได้

ตำแหน่งของ `config/` คือโฟลเดอร์แม่ของที่ที่ `Main` ถูกโหลด เช่น ถ้ารันจาก jar ให้วาง `config/` ไว้ข้างไฟล์ jar และถ้ารันจากโฟลเดอร์ `.class` ให้วางไว้ที่โฟลเดอร์ก่อนหน้านั้นหนึ่งระดับ

```json
{
  "debug": false,
  "name": "arc"
}
```

## โครงสร้างโปรแกรม

โปรแกรมแบ่งเป็น section และเริ่มรันที่ label ที่กำหนดด้วย `global` (ค่าเริ่มต้นคือ `_start`) เมื่อเจอ `section text`

```asm
section data
msg: string "Hello"

global _start
section text

_start:
    puts msg
    halt
```

- `section` มี 3 ชนิด: `bss`, `data`, `text`
- ตัวแปรต้องประกาศใน `section data` ส่วนคำสั่งทั้งหมดต้องอยู่ใน `section text`
- `global <label>` ต้องมาก่อน `section text`
- comment ขึ้นต้นด้วย `;`
- label ลงท้ายด้วย `:` จะอยู่บรรทัดเดียวกับคำสั่งหรือบรรทัดของตัวเองก็ได้
- operand คั่นด้วย `,` หรือช่องว่าง
- ชื่อคำสั่งไม่สนตัวพิมพ์เล็กใหญ่

## ชนิดข้อมูล

ประกาศใน `section data` ด้วยรูปแบบ `ชื่อ: ชนิด ค่า`

| ชนิด | ตัวอย่าง |
|---|---|
| `int` | `count: int 10` |
| `float` | `ratio: float 1.5f` |
| `double` | `pi: double 3.14159` |
| `char` | `ch: char 'A'` |
| `string` | `msg: string "Hi\n"` |

Escape ที่รองรับ: `\n` `\t` `\r` `\"` `\'` `\\`

## Operand

คำสั่งส่วนใหญ่รับ operand ได้ดังนี้

| รูปแบบ | ความหมาย |
|---|---|
| `t0`, `a1`, ... | register |
| `count` | ตัวแปรใน RAM |
| `[count]` | ตัวแปรใน RAM (เขียนแบบชัดเจน) |
| `[t0]` | ตัวแปรใน RAM ที่ชื่ออยู่ใน `t0` (pointer, ใช้คู่กับ `lea`) |
| `10`, `-5`, `0xFF`, `0b101`, `3.14` | ตัวเลข |
| `"text"`, `'c'` | string / char |

การเขียนลงตัวแปรใน RAM จะแปลงค่าให้ตรงกับชนิดของตัวแปรอัตโนมัติ (เช่น เขียนลง `int` ก็ได้ int)

### Register

`r0 ra sp gp`, `t0`-`t11`, `s0`-`s7`, `a0`-`a7` เริ่มต้นที่ `0` ทั้งหมด

`sp` สะท้อนขนาดของ stack หลักและเขียนทับเองไม่ได้ นอกนั้นใช้เป็น register ทั่วไปได้

## รายการคำสั่ง

### โหลดและย้ายข้อมูล

| คำสั่ง | ความหมาย |
|---|---|
| `lim dst, literal` | โหลดค่าคงที่ลง dst (รับเฉพาะ literal) |
| `mov dst, src` | คัดลอกค่า (`ld` และ `st` คือชื่อเรียกอีกแบบ) |
| `lea dst, name` | เก็บ "address" (ชื่อ) ของตัวแปรไว้ใน dst |

### คำนวณ

| คำสั่ง | ความหมาย |
|---|---|
| `add` `sub` `mul` `div` `mod` | `op dst, a, b` คือ `dst = a op b` ส่วน `op dst, a` คือ `dst = dst op a` |
| `inc dst` / `dec dst` | เพิ่ม / ลด 1 |

- ถ้ามี float หรือ double อยู่ด้วยจะคิดเป็นทศนิยม ไม่เช่นนั้นเป็นจำนวนเต็ม
- `add` กับ string คือการต่อ string
- หารหรือ mod ด้วย 0 จะ error

### เปรียบเทียบและกระโดด

`cmp a, b` เก็บผลเปรียบเทียบไว้ใน flag (น้อยกว่า / เท่ากัน / มากกว่า) แล้วใช้คำสั่งกระโดดตามเงื่อนไข

| คำสั่ง | กระโดดเมื่อ |
|---|---|
| `jump` / `jmp` | เสมอ |
| `je` / `jz` | เท่ากัน |
| `jne` / `jnz` | ไม่เท่ากัน |
| `jl` / `jle` | น้อยกว่า / น้อยกว่าหรือเท่ากับ |
| `jg` / `jge` | มากกว่า / มากกว่าหรือเท่ากับ |

ถ้าเปรียบเทียบกับ string จะเทียบตามลำดับตัวอักษร

### ฟังก์ชัน

| คำสั่ง | ความหมาย |
|---|---|
| `call label` | เก็บตำแหน่งกลับแล้วกระโดดไป label |
| `ret` | กลับไปหลัง `call` ล่าสุด (ถ้าไม่มีที่ให้กลับ โปรแกรมจบ) |

### Stack

| คำสั่ง | ความหมาย |
|---|---|
| `push src` | ใส่ค่าลง stack |
| `pop dst` | เอาค่าบนสุดออกมาเก็บใน dst |
| `peek dst` | ดูค่าบนสุดโดยไม่เอาออก |
| `dup` | ทำสำเนาค่าบนสุด |
| `swap` | สลับค่าบนสุด 2 ตัว |

### LabelStack (stack แยกตามชื่อ)

แต่ละชื่อคือ stack ของตัวเอง และถูกสร้างอัตโนมัติเมื่อ `lpush` ครั้งแรก

| คำสั่ง | ความหมาย |
|---|---|
| `lpush name, src` | ใส่ค่าลง stack ชื่อ `name` |
| `lpop dst, name` | เอาค่าบนสุดออก |
| `lpeek dst, name` | ดูค่าบนสุด |
| `lsize dst, name` | จำนวนค่าใน stack |
| `lclear name` | ล้าง stack |

### RAM

| คำสั่ง | ความหมาย |
|---|---|
| `alloc name type [value]` | สร้างตัวแปรใหม่ตอนรัน (ไม่ใส่ค่าจะได้ค่าเริ่มต้นของชนิดนั้น) |
| `free name` | ลบตัวแปร |
| `dump` | แสดง register (ที่ไม่ใช่ 0), RAM, stack ทุกชนิด และ flag |

### อื่น ๆ

| คำสั่ง | ความหมาย |
|---|---|
| `puts a, b, ...` | พิมพ์ทุก operand ต่อกันแล้วขึ้นบรรทัดใหม่ |
| `pass` | ไม่ทำอะไร |
| `halt` / `exit` | จบโปรแกรม |

## ตัวอย่าง

### รวมเลข 1 ถึง 10

```asm
section data
msg: string "Sum 1..10 = "

global _start
section text

_start:
    lim t0, 10
    lim t1, 0
loop:
    add t1, t0
    dec t0
    cmp t0, 0
    jg loop
    puts msg, t1        ; Sum 1..10 = 55
    halt
```

### ฟังก์ชันและ stack

```asm
section data
global _start
section text

_start:
    push 7
    push 5
    call sum_top
    puts "result = ", a0    ; result = 12
    halt

sum_top:
    pop t0
    pop t1
    add a0, t0, t1
    ret
```

### RAM และ pointer

```asm
section data
score: int 0

global _start
section text

_start:
    lea t0, score
    mov [t0], 42        ; เขียนลง score ผ่าน pointer
    puts "score = ", score
    alloc temp double 1.5
    mul temp, 2
    puts temp           ; 3.0
    free temp
    halt
```

## ข้อความ error

รูปแบบ `[ชื่อ] ข้อความ InLine N` แล้วโปรแกรมจะจบด้วย exit code 1

ตัวเลข `N` คือลำดับบรรทัดของโปรแกรมหลัง tokenize (นับจาก 0 และไม่นับบรรทัดว่างหรือ comment ล้วน) ไม่ใช่เลขบรรทัดในไฟล์ต้นฉบับโดยตรง เปิด `-d` เพื่อดูตาราง `PC <N>: ...` เทียบได้

| ชื่อ | สาเหตุ |
|---|---|
| `SyntaxError` | รูปแบบคำสั่งหรือจำนวน operand ผิด / ไม่รู้จักคำสั่ง |
| `SectionError` | ใช้คำสั่งผิด section |
| `LabelError` | label ซ้ำหรือไม่พบ |
| `DataError` | ตัวแปรซ้ำ ไม่พบ หรือ operand ไม่รู้จัก |
| `TypeError` | ชนิดหรือค่าไม่ตรงกัน |
| `MathError` | หารหรือ mod ด้วย 0 |
| `StackError` | stack ว่าง / call stack ลึกเกินไป |
| `RegisterError` | เขียนทับ `sp` |
| `TokenizerError` | string หรือ char ไม่ปิด |

## ข้อจำกัดปัจจุบัน

- ยังไม่มี `and` / `or` / `xor` / shift, การรับ input และการส่ง argument ผ่าน register แบบอัตโนมัติ
- `bss` ยังเป็นแค่ section ที่ประกาศได้ ยังไม่มีคำสั่งเฉพาะ
- ตัวแปรใน RAM เป็นแบบมีชื่อ ยังไม่มี address เป็นตัวเลขหรืออาร์เรย์# ArcAssembly
