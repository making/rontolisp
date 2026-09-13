import fs from 'node:fs';
const { instance } = await WebAssembly.instantiate(fs.readFileSync(process.argv[2]), {});
const e = instance.exports;
const t = (name, ...a) => { try { console.log(`${name}(${a}) = ${e[name](...a)}`); } catch (err) { console.log(`${name}(${a}) trap: ${err.message}`); } };
t('Square', 40000);        // 1.6e9 fits i32
t('Square', 65536);        // 2^32: past the s32 boundary
t('SquareMod', 65536);     // 4294967296 mod 1000 = 296: intermediate past 2^32, RESULT fits
t('FactDigits', 12);       // 12! = 479001600 fits i32
t('FactDigits', 13);       // 13! = 6227020800 > 2^31, mod 10000 = 800
t('FactDigits', 20);       // 20! = 2432902008176640000 < 2^63, mod 10000 = 0
