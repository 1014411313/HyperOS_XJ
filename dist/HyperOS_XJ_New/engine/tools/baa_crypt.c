/* baa_crypt.c — BAA 载荷 AES-128-CBC + base64 编解码（无外部依赖，可静态编译）
 *
 * 用法：
 *   baa_crypt decrypt <in> <out>          载荷（可带 BAA_ENC: 前缀）-> 明文 JSON
 *   baa_crypt encrypt <in> <out> [ivhex]  明文 JSON -> BAA_ENC:<base64>
 *   baa_crypt iv <in>                     打印载荷内自带的 IV（32 位小写 hex）
 *
 * 载荷格式：BAA_ENC: + base64( IV(16B) || AES-128-CBC(PKCS#7 填充) )
 * 解密一律使用文件内自带的 IV；加密默认写死 10 11 12 .. 1f，
 * 需要保持原文件 IV 时用第三个参数传入（改写既有载荷必须保持，否则首块会错）。
 *
 * 设备端没有 openssl，AES 与 base64 只能自带；静态链接后随模块分发。
 * 编译：gcc -static -O2 -o baa_crypt baa_crypt.c
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NB 4
#define NK 4
#define NR 10
#define BLK 16
#define KEYLEN 16

typedef uint8_t state_t[4][4];

static const uint8_t SBOX[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

static uint8_t RSBOX[256];
static const uint8_t Rcon[11] = { 0x8d, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36 };

static const uint8_t KEY[KEYLEN] = {
    'm', 'i', 'c', 'h', 'a', 'r', 'g', 'e', 'r', 'b', 'a', 't', 't', 'e', 'r', 'y'
};
static const uint8_t FIXED_IV[BLK] = {
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
    0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
};
static const char B64T[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static const char PREFIX[] = "BAA_ENC:";
#define PREFIX_LEN (sizeof(PREFIX) - 1)

static void init_tables(void)
{
    int i;
    for (i = 0; i < 256; ++i)
        RSBOX[SBOX[i]] = (uint8_t)i;
}

static uint8_t xtime(uint8_t x)
{
    return (uint8_t)((x << 1) ^ (((x >> 7) & 1) * 0x1b));
}

static uint8_t Multiply(uint8_t x, uint8_t y)
{
    return (uint8_t)(((y & 1) * x) ^
                     (((y >> 1) & 1) * xtime(x)) ^
                     (((y >> 2) & 1) * xtime(xtime(x))) ^
                     (((y >> 3) & 1) * xtime(xtime(xtime(x)))) ^
                     (((y >> 4) & 1) * xtime(xtime(xtime(xtime(x))))));
}

static void KeyExpansion(uint8_t *RoundKey, const uint8_t *Key)
{
    unsigned i, j, k;
    uint8_t tempa[4];

    for (i = 0; i < NK; ++i) {
        RoundKey[(i * 4) + 0] = Key[(i * 4) + 0];
        RoundKey[(i * 4) + 1] = Key[(i * 4) + 1];
        RoundKey[(i * 4) + 2] = Key[(i * 4) + 2];
        RoundKey[(i * 4) + 3] = Key[(i * 4) + 3];
    }

    for (i = NK; i < NB * (NR + 1); ++i) {
        k = (i - 1) * 4;
        tempa[0] = RoundKey[k + 0];
        tempa[1] = RoundKey[k + 1];
        tempa[2] = RoundKey[k + 2];
        tempa[3] = RoundKey[k + 3];

        if (i % NK == 0) {
            uint8_t u8tmp = tempa[0];
            tempa[0] = tempa[1];
            tempa[1] = tempa[2];
            tempa[2] = tempa[3];
            tempa[3] = u8tmp;
            tempa[0] = SBOX[tempa[0]];
            tempa[1] = SBOX[tempa[1]];
            tempa[2] = SBOX[tempa[2]];
            tempa[3] = SBOX[tempa[3]];
            tempa[0] = (uint8_t)(tempa[0] ^ Rcon[i / NK]);
        }
        j = i * 4;
        k = (i - NK) * 4;
        RoundKey[j + 0] = (uint8_t)(RoundKey[k + 0] ^ tempa[0]);
        RoundKey[j + 1] = (uint8_t)(RoundKey[k + 1] ^ tempa[1]);
        RoundKey[j + 2] = (uint8_t)(RoundKey[k + 2] ^ tempa[2]);
        RoundKey[j + 3] = (uint8_t)(RoundKey[k + 3] ^ tempa[3]);
    }
}

static void AddRoundKey(uint8_t round, state_t *state, const uint8_t *RoundKey)
{
    uint8_t i, j;
    for (i = 0; i < 4; ++i)
        for (j = 0; j < 4; ++j)
            (*state)[i][j] ^= RoundKey[(round * NB * 4) + (i * NB) + j];
}

static void SubBytes(state_t *state)
{
    uint8_t i, j;
    for (i = 0; i < 4; ++i)
        for (j = 0; j < 4; ++j)
            (*state)[i][j] = SBOX[(*state)[i][j]];
}

static void InvSubBytes(state_t *state)
{
    uint8_t i, j;
    for (i = 0; i < 4; ++i)
        for (j = 0; j < 4; ++j)
            (*state)[i][j] = RSBOX[(*state)[i][j]];
}

static void ShiftRows(state_t *state)
{
    uint8_t temp;

    temp = (*state)[0][1];
    (*state)[0][1] = (*state)[1][1];
    (*state)[1][1] = (*state)[2][1];
    (*state)[2][1] = (*state)[3][1];
    (*state)[3][1] = temp;

    temp = (*state)[0][2];
    (*state)[0][2] = (*state)[2][2];
    (*state)[2][2] = temp;
    temp = (*state)[1][2];
    (*state)[1][2] = (*state)[3][2];
    (*state)[3][2] = temp;

    temp = (*state)[0][3];
    (*state)[0][3] = (*state)[3][3];
    (*state)[3][3] = (*state)[2][3];
    (*state)[2][3] = (*state)[1][3];
    (*state)[1][3] = temp;
}

static void InvShiftRows(state_t *state)
{
    uint8_t temp;

    temp = (*state)[3][1];
    (*state)[3][1] = (*state)[2][1];
    (*state)[2][1] = (*state)[1][1];
    (*state)[1][1] = (*state)[0][1];
    (*state)[0][1] = temp;

    temp = (*state)[0][2];
    (*state)[0][2] = (*state)[2][2];
    (*state)[2][2] = temp;
    temp = (*state)[1][2];
    (*state)[1][2] = (*state)[3][2];
    (*state)[3][2] = temp;

    temp = (*state)[0][3];
    (*state)[0][3] = (*state)[1][3];
    (*state)[1][3] = (*state)[2][3];
    (*state)[2][3] = (*state)[3][3];
    (*state)[3][3] = temp;
}

static void MixColumns(state_t *state)
{
    uint8_t i, Tmp, Tm, t;
    for (i = 0; i < 4; ++i) {
        t = (*state)[i][0];
        Tmp = (uint8_t)((*state)[i][0] ^ (*state)[i][1] ^ (*state)[i][2] ^ (*state)[i][3]);
        Tm = (uint8_t)((*state)[i][0] ^ (*state)[i][1]);
        Tm = xtime(Tm);
        (*state)[i][0] ^= (uint8_t)(Tm ^ Tmp);
        Tm = (uint8_t)((*state)[i][1] ^ (*state)[i][2]);
        Tm = xtime(Tm);
        (*state)[i][1] ^= (uint8_t)(Tm ^ Tmp);
        Tm = (uint8_t)((*state)[i][2] ^ (*state)[i][3]);
        Tm = xtime(Tm);
        (*state)[i][2] ^= (uint8_t)(Tm ^ Tmp);
        Tm = (uint8_t)((*state)[i][3] ^ t);
        Tm = xtime(Tm);
        (*state)[i][3] ^= (uint8_t)(Tm ^ Tmp);
    }
}

static void InvMixColumns(state_t *state)
{
    int i;
    uint8_t a, b, c, d;
    for (i = 0; i < 4; ++i) {
        a = (*state)[i][0];
        b = (*state)[i][1];
        c = (*state)[i][2];
        d = (*state)[i][3];

        (*state)[i][0] = (uint8_t)(Multiply(a, 0x0e) ^ Multiply(b, 0x0b) ^ Multiply(c, 0x0d) ^ Multiply(d, 0x09));
        (*state)[i][1] = (uint8_t)(Multiply(a, 0x09) ^ Multiply(b, 0x0e) ^ Multiply(c, 0x0b) ^ Multiply(d, 0x0d));
        (*state)[i][2] = (uint8_t)(Multiply(a, 0x0d) ^ Multiply(b, 0x09) ^ Multiply(c, 0x0e) ^ Multiply(d, 0x0b));
        (*state)[i][3] = (uint8_t)(Multiply(a, 0x0b) ^ Multiply(b, 0x0d) ^ Multiply(c, 0x09) ^ Multiply(d, 0x0e));
    }
}

static void Cipher(state_t *state, const uint8_t *RoundKey)
{
    uint8_t round;
    AddRoundKey(0, state, RoundKey);
    for (round = 1; round < NR; ++round) {
        SubBytes(state);
        ShiftRows(state);
        MixColumns(state);
        AddRoundKey(round, state, RoundKey);
    }
    SubBytes(state);
    ShiftRows(state);
    AddRoundKey(NR, state, RoundKey);
}

static void InvCipher(state_t *state, const uint8_t *RoundKey)
{
    uint8_t round;
    AddRoundKey(NR, state, RoundKey);
    for (round = NR - 1; round > 0; --round) {
        InvShiftRows(state);
        InvSubBytes(state);
        AddRoundKey(round, state, RoundKey);
        InvMixColumns(state);
    }
    InvShiftRows(state);
    InvSubBytes(state);
    AddRoundKey(0, state, RoundKey);
}

static void cbc_encrypt(const uint8_t *in, size_t len, uint8_t *out, const uint8_t *iv)
{
    uint8_t rk[16 * (NR + 1)];
    uint8_t prev[BLK];
    size_t off;
    KeyExpansion(rk, KEY);
    memcpy(prev, iv, BLK);
    for (off = 0; off < len; off += BLK) {
        state_t st;
        int i;
        uint8_t blk[BLK];
        for (i = 0; i < BLK; ++i)
            blk[i] = (uint8_t)(in[off + i] ^ prev[i]);
        memcpy(st, blk, BLK);
        Cipher(&st, rk);
        memcpy(out + off, st, BLK);
        memcpy(prev, out + off, BLK);
    }
}

static void cbc_decrypt(const uint8_t *in, size_t len, uint8_t *out, const uint8_t *iv)
{
    uint8_t rk[16 * (NR + 1)];
    uint8_t prev[BLK];
    size_t off;
    KeyExpansion(rk, KEY);
    memcpy(prev, iv, BLK);
    for (off = 0; off < len; off += BLK) {
        state_t st;
        uint8_t ct[BLK];
        memcpy(st, in + off, BLK);
        InvCipher(&st, rk);
        memcpy(ct, st, BLK);
        for (int i = 0; i < BLK; ++i)
            out[off + i] = (uint8_t)(ct[i] ^ prev[i]);
        memcpy(prev, in + off, BLK);
    }
}

static size_t b64_encode(const uint8_t *in, size_t n, char *out)
{
    size_t i = 0, o = 0;
    while (i + 3 <= n) {
        uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8) | in[i + 2];
        out[o++] = B64T[(v >> 18) & 63];
        out[o++] = B64T[(v >> 12) & 63];
        out[o++] = B64T[(v >> 6) & 63];
        out[o++] = B64T[v & 63];
        i += 3;
    }
    if (n - i == 1) {
        uint32_t v = (uint32_t)in[i] << 16;
        out[o++] = B64T[(v >> 18) & 63];
        out[o++] = B64T[(v >> 12) & 63];
        out[o++] = '=';
        out[o++] = '=';
    } else if (n - i == 2) {
        uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8);
        out[o++] = B64T[(v >> 18) & 63];
        out[o++] = B64T[(v >> 12) & 63];
        out[o++] = B64T[(v >> 6) & 63];
        out[o++] = '=';
    }
    out[o] = '\0';
    return o;
}

static int b64_value(int c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static int b64_decode(const char *s, size_t n, uint8_t *out, size_t *outn)
{
    uint32_t acc = 0;
    int bits = 0, pad = 0;
    size_t i, o = 0;
    for (i = 0; i < n; ++i) {
        int c = (unsigned char)s[i];
        int v;
        if (c == '\n' || c == '\r' || c == '\t' || c == ' ') continue;
        if (c == '=') { pad++; continue; }
        if (pad) return -1;              /* 填充符之后不允许再有数据 */
        v = b64_value(c);
        if (v < 0) return -1;
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[o++] = (uint8_t)((acc >> bits) & 0xff);
        }
    }
    if (pad > 2) return -1;
    *outn = o;
    return 0;
}

static uint8_t *slurp(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    long sz;
    size_t got;
    uint8_t *buf;
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    sz = ftell(f);
    if (sz < 0) { fclose(f); return NULL; }
    rewind(f);
    buf = (uint8_t *)malloc((size_t)sz + 1);
    if (!buf) { fclose(f); return NULL; }
    got = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    buf[got] = 0;
    *len = got;
    return buf;
}

static int spew(const char *path, const uint8_t *data, size_t len)
{
    FILE *f = fopen(path, "wb");
    int rc;
    if (!f) return -1;
    if (len && fwrite(data, 1, len, f) != len) { fclose(f); return -1; }
    rc = fclose(f);
    return rc == 0 ? 0 : -1;
}

static int do_decrypt(const char *out, const uint8_t *in, size_t n)
{
    const uint8_t *b64 = in;
    size_t bn = n, blobn = 0;
    uint8_t *blob, *pt;
    size_t ctn, ptl;
    int pad, i;

    if (n >= PREFIX_LEN && memcmp(in, PREFIX, PREFIX_LEN) == 0) {
        b64 = in + PREFIX_LEN;
        bn = n - PREFIX_LEN;
    }
    blob = (uint8_t *)malloc(bn / 4 * 3 + 8);
    if (!blob) { fprintf(stderr, "baa_crypt: out of memory\n"); return 1; }
    if (b64_decode((const char *)b64, bn, blob, &blobn) != 0) {
        fprintf(stderr, "baa_crypt: invalid base64 payload\n");
        free(blob);
        return 3;
    }
    if (blobn < 2 * BLK || (blobn - BLK) % BLK != 0) {
        fprintf(stderr, "baa_crypt: bad ciphertext length %lu\n", (unsigned long)blobn);
        free(blob);
        return 3;
    }
    ctn = blobn - BLK;
    pt = (uint8_t *)malloc(ctn);
    if (!pt) { free(blob); fprintf(stderr, "baa_crypt: out of memory\n"); return 1; }
    cbc_decrypt(blob + BLK, ctn, pt, blob);

    pad = pt[ctn - 1];
    if (pad < 1 || pad > BLK) {
        fprintf(stderr, "baa_crypt: bad PKCS#7 padding\n");
        free(blob); free(pt);
        return 4;
    }
    for (i = 1; i <= pad; ++i) {
        if (pt[ctn - i] != pad) {
            fprintf(stderr, "baa_crypt: bad PKCS#7 padding\n");
            free(blob); free(pt);
            return 4;
        }
    }
    ptl = ctn - (size_t)pad;
    if (spew(out, pt, ptl) != 0) {
        fprintf(stderr, "baa_crypt: cannot write %s\n", out);
        free(blob); free(pt);
        return 1;
    }
    free(blob);
    free(pt);
    return 0;
}

static int hex_value(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int parse_hex_iv(const char *s, uint8_t *iv)
{
    int i;
    if (strlen(s) != 2 * BLK) return -1;
    for (i = 0; i < BLK; ++i) {
        int hi = hex_value((unsigned char)s[2 * i]);
        int lo = hex_value((unsigned char)s[2 * i + 1]);
        if (hi < 0 || lo < 0) return -1;
        iv[i] = (uint8_t)((hi << 4) | lo);
    }
    return 0;
}

static int do_iv(const uint8_t *in, size_t n)
{
    const uint8_t *b64 = in;
    size_t bn = n, blobn = 0;
    uint8_t *blob;
    int i;

    if (n >= PREFIX_LEN && memcmp(in, PREFIX, PREFIX_LEN) == 0) {
        b64 = in + PREFIX_LEN;
        bn = n - PREFIX_LEN;
    }
    blob = (uint8_t *)malloc(bn / 4 * 3 + 8);
    if (!blob) { fprintf(stderr, "baa_crypt: out of memory\n"); return 1; }
    if (b64_decode((const char *)b64, bn, blob, &blobn) != 0 || blobn < BLK) {
        fprintf(stderr, "baa_crypt: invalid base64 payload\n");
        free(blob);
        return 3;
    }
    for (i = 0; i < BLK; ++i) printf("%02x", blob[i]);
    printf("\n");
    free(blob);
    return 0;
}

static int do_encrypt(const char *out, const uint8_t *in, size_t n, const uint8_t *iv)
{
    size_t pad = BLK - (n % BLK);
    size_t pl = n + pad, blobn = BLK + pl;
    uint8_t *padded, *blob;
    char *b64;
    size_t b64cap = 4 * ((blobn + 2) / 3) + 1;
    int rc;

    padded = (uint8_t *)malloc(pl);
    blob = (uint8_t *)malloc(blobn);
    b64 = (char *)malloc(PREFIX_LEN + b64cap + 1);
    if (!padded || !blob || !b64) {
        fprintf(stderr, "baa_crypt: out of memory\n");
        free(padded); free(blob); free(b64);
        return 1;
    }
    memcpy(padded, in, n);
    memset(padded + n, (int)pad, pad);
    memcpy(blob, iv, BLK);
    cbc_encrypt(padded, pl, blob + BLK, iv);
    memcpy(b64, PREFIX, PREFIX_LEN);
    b64_encode(blob, blobn, b64 + PREFIX_LEN);

    rc = spew(out, (const uint8_t *)b64, strlen(b64));
    if (rc != 0) fprintf(stderr, "baa_crypt: cannot write %s\n", out);
    free(padded);
    free(blob);
    free(b64);
    return rc == 0 ? 0 : 1;
}

int main(int argc, char **argv)
{
    uint8_t *in;
    size_t n = 0;
    int rc;

    const uint8_t *iv = FIXED_IV;
    uint8_t ivbuf[BLK];

    init_tables();
    if (argc < 3) {
        fprintf(stderr, "usage: %s decrypt|encrypt|iv <in> [out] [ivhex]\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "iv") == 0) {
        if (argc != 3) {
            fprintf(stderr, "usage: %s iv <in>\n", argv[0]);
            return 2;
        }
        in = slurp(argv[2], &n);
        if (!in) { fprintf(stderr, "baa_crypt: cannot read %s\n", argv[2]); return 1; }
        rc = do_iv(in, n);
        free(in);
        return rc;
    }
    if (argc < 4 || argc > 5) {
        fprintf(stderr, "usage: %s decrypt|encrypt <in> <out> [ivhex]\n", argv[0]);
        return 2;
    }
    if (argc == 5) {
        if (parse_hex_iv(argv[4], ivbuf) != 0) {
            fprintf(stderr, "baa_crypt: bad iv hex (need 32 hex chars)\n");
            return 2;
        }
        iv = ivbuf;
    }
    in = slurp(argv[2], &n);
    if (!in) {
        fprintf(stderr, "baa_crypt: cannot read %s\n", argv[2]);
        return 1;
    }
    if (strcmp(argv[1], "decrypt") == 0) {
        rc = do_decrypt(argv[3], in, n);
    } else if (strcmp(argv[1], "encrypt") == 0) {
        rc = do_encrypt(argv[3], in, n, iv);
    } else {
        fprintf(stderr, "usage: %s decrypt|encrypt|iv <in> [out] [ivhex]\n", argv[0]);
        rc = 2;
    }
    free(in);
    return rc;
}
