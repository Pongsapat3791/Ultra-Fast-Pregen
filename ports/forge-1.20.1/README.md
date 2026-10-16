# Ultra Fast Pregen สำหรับ Forge 1.20.1

รุ่น Forge ของ Ultra Fast Pregen ใช้โค้ดชุดเดียวกับรุ่น Fabric 1.20.1 (`src/` ของโปรเจกต์หลัก) ต่างกันแค่ตัวเริ่มของ loader
(`com.example.pregen.forge.ForgeEntrypoint` แทน `com.example.pregen.fabric.FabricEntrypoint`)
ทุกการแก้ในรุ่น Fabric จึงมีในรุ่น Forge ด้วย และเลขรุ่นตรงกัน (`version` ใน `gradle.properties` ของโปรเจกต์หลัก)

คำสั่ง ไฟล์ตั้งค่า ไฟล์ save และวิธีทำงานเหมือนรุ่น Fabric ทุกอย่าง อ่านได้ใน [README หลัก](../../README.md)

- ต้องการ: Forge 1.20.1 (47.x), Java 17 ขึ้นไป
- ไฟล์: `ports/forge-1.20.1/build/libs/ultrafastpregen-forge-1.20.1-x.jar`
- ใส่ฝั่ง server อย่างเดียวก็ได้ ผู้เล่นเข้า server ได้ทั้งที่มีและไม่มี mod นี้

## Build และทดสอบ (จากโฟลเดอร์โปรเจกต์หลัก)

```powershell
.\gradlew.bat -p ports/forge-1.20.1 build
.\gradlew.bat -p ports/forge-1.20.1 runTerrainBenchmark
.\gradlew.bat -p ports/forge-1.20.1 runPartialBatchCheck
```

- ใช้ ModDevGradle (legacyforge) กับ Forge 47.4.28 ชื่อใน mixin แปลงเป็นชื่อของ Forge ผ่าน `pregen.refmap.json` ตอน build
- `runTerrainBenchmark` และ `runPartialBatchCheck` คือเทสต์ชุดเดียวกับรุ่น Fabric (ไฟล์ใน `src/test` ของโปรเจกต์หลัก) รันบน server ของ Forge
  `runPartialBatchCheck` ใช้โลกทดสอบที่ `build/partial-batch-check` ตั้ง `level-name` ใหม่ทุกครั้ง (ค่าเริ่มต้นมาจาก `checks/`)

## ผลทดสอบ (1.20.1-13)

- **เทสต์ชุดเต็มบน Forge 47.4.28:** ผ่านทุกข้อ ได้ hash ของบล็อกเท่ากับรุ่น Fabric ทุกตัว (ความเหมือน vanilla รอบหมู่บ้าน, ลำดับ features, ขั้นตอนของ Minecraft)
  รวมทั้ง preset อื่นของ vanilla, รอยต่อ batch, หยุดแล้วรันต่อ, ต่อกับชังก์ที่มีอยู่แล้ว, nether, end
  และแสงทุกบล็อกตรงกับ light engine ของ Minecraft (`LIGHT_PARITY_CHECK`)
- **jar ที่แจกจริงบน server Forge (i9-11900T ES, 16 thread, `-Xmx8G`), พื้นที่ 3k, seed 42, center 100000 100000:**
  5 นาที 22 วินาที, 109.6 ชังก์/วินาที (รุ่น Fabric บนเครื่องเดียวกัน 113.3; รุ่น 12 ที่แสงยังผิดได้ 112.8 และ 120.6) ใช้ทางลัดของ terrain ครบ
  ช้ากว่า Fabric ราว 3% (รุ่น 12 วัดได้ 6% ที่ขั้น terrain โดยเฉพาะ surface) ยังไม่ได้หาว่าส่วนไหนของ Forge ทำให้ช้า (วัดรอบเดียว)
- **เทียบทั้งโลกกับรุ่น Fabric (รุ่น 12, 28,900 ชังก์):** ต่างกัน 50 บล็อก ทั้งหมดเป็นรังผึ้ง (Minecraft สุ่มตำแหน่งรังผึ้งแบบไม่มี seed)
