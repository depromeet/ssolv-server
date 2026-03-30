/**
 * K6 부하 테스트 스크립트
 * 원본: nGrinder SsolvMainFlow.groovy
 *
 * 실행 방법:
 *   k6 run --out web-dashboard k6/ssolv-main-flow.js
 *
 * 웹 대시보드 확인:
 *   http://localhost:5665
 *
 * 환경변수로 VU/Duration 변경 가능:
 *   K6_VU=100 K6_DURATION=5m k6 run --out web-dashboard k6/ssolv-main-flow.js
 */

import http from "k6/http";
import { check, sleep, fail } from "k6";
import { randomIntBetween } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

// ──────────────────────────────────────────
// 설정
// ──────────────────────────────────────────
const BASE_URL = "https://api.ssolv.site/api/v1";
const TOKEN =
    "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIyOCIsImF1dGhvcml0aWVzIjoiUk9MRV9VU0VSIiwidG9rZW5UeXBlIjoiQUNDRVNTIiwiZW1haWwiOiJtaW5ldW05QG5hdGUuY29tIiwiaWF0IjoxNzc0ODc1OTM1LCJleHAiOjE3NzQ5MTkxMzV9.EHigcUNltpEHixMkGIKI310_fNEtpguhMd4VGuZLHthcq2XfUc6nEg_hyAJK3lTkYdxyJpoIE_jquiCnfndGcw";

const THINK_MIN = 0.3; // seconds
const THINK_MAX = 0.7; // seconds

const STATION_COUNT = 10;

// ──────────────────────────────────────────
// 부하 시나리오 (nGrinder 기본값과 유사하게)
// ──────────────────────────────────────────
export const options = {
    scenarios: {
        main_flow: {
            executor: "ramping-vus",
            startVUs: 0,
            stages: [
                { duration: "30s", target: __ENV.K6_VU ? parseInt(__ENV.K6_VU) : 50 }, // 램프업
                { duration: __ENV.K6_DURATION || "3m", target: __ENV.K6_VU ? parseInt(__ENV.K6_VU) : 50 }, // 유지
                { duration: "15s", target: 0 }, // 램프다운
            ],
            gracefulRampDown: "10s",
        },
    },
    thresholds: {
        // 성공률 95% 이상
        http_req_failed: [{ threshold: "rate<0.05", abortOnFail: false }],
        // p99 응답시간 2초 이하
        http_req_duration: ["p(99)<2000"],
        // 각 API별 thresholds
        "http_req_duration{name:GET /meetings}": ["p(95)<1500"],
        "http_req_duration{name:GET /meetings/{token}}": ["p(95)<1500"],
        "http_req_duration{name:GET /meetings/validate-invite}": ["p(95)<1500"],
    },
};

// ──────────────────────────────────────────
// 공통 헤더 빌더
// ──────────────────────────────────────────
function authHeaders(contentType = false) {
    const h = {
        Authorization: `Bearer ${TOKEN}`,
        Accept: "application/json",
    };
    if (contentType) h["Content-Type"] = "application/json";
    return h;
}

// VU별 상태 저장 (토큰)
let myToken = null;

// ──────────────────────────────────────────
// default function: 가중치 기반 현실 유저 플로우
// ──────────────────────────────────────────
export default function () {
    // 1. 초기화: 토큰이 없다면 첫 번째 루프에서 무조건 생성 (의존성 해결)
    if (!myToken) {
        createMeeting();
        // 초기화 시점에도 think time 부여할지 결정 (여기서는 즉시 다음 루프 준비)
        return;
    }

    // 2. 가중치 기반 시나리오 (제시해주신 비율 반영)
    const rand = Math.random() * 100;

    if (rand < 10) {
        // [10%] POST /meetings (새 미팅 생성 및 토큰 갱신)
        createMeeting();
    } else if (rand < 35) {
        // [25%] GET /meetings (목록 조회)
        getMeetings();
    } else if (rand < 80) {
        // [45%] GET /meetings/{token} (상세 조회)
        getMeetingDetail(myToken);
    } else {
        // [20%] GET /meetings/validate-invite (초대 검증)
        validateInvite(myToken);
    }

    // Think time (300~700ms)
    sleep(randomIntBetween(THINK_MIN * 1000, THINK_MAX * 1000) / 1000);
}

// ──────────────────────────────────────────
// API 호출 함수들 (모듈화)
// ──────────────────────────────────────────

function createMeeting() {
    const body = JSON.stringify({
        name: `부하테스트-${__VU}-${Date.now()}`,
        attendeeCount: 2,
        stationId: (__VU % 10) + 1,
        endAt: "2026-12-31T23:59:59",
    });

    const res = http.post(`${BASE_URL}/meetings`, body, {
        headers: authHeaders(true),
        tags: { name: "POST /meetings" },
        timeout: "15s",
    });

    if (res && (res.status === 200 || res.status === 201)) {
        try {
            const json = res.json();
            const url = json?.data?.validateTokenUrl ?? "";
            const extracted = url.split("token=")[1] ?? null;
            if (extracted) {
                myToken = extracted;
                // console.log(`[VU ${__VU}] 미팅 생성/갱신 완료: token=${myToken}`);
            } else {
                console.warn(`[VU ${__VU}] POST 성공했으나 토큰 추출 실패. Body: ${res.body}`);
            }
        } catch (e) {
            console.error(`[VU ${__VU}] POST 응답 파싱 실패: ${e.message}`);
        }
    } else {
        // console.error(`[VU ${__VU}] POST /meetings 실패: status=${res ? res.status : "timeout"}`);
    }
}

function getMeetings() {
    const res = http.get(`${BASE_URL}/meetings`, {
        headers: authHeaders(),
        tags: { name: "GET /meetings" },
    });

    check(res, {
        "GET /meetings: not 5xx": (r) => r.status < 500,
    });
}

function getMeetingDetail(token) {
    if (!token) return;
    const res = http.get(`${BASE_URL}/meetings/${token}`, {
        headers: authHeaders(),
        tags: { name: "GET /meetings/{token}" },
    });

    check(res, {
        "GET /meetings/{token}: not 5xx": (r) => r.status < 500,
    });
}

function validateInvite(token) {
    if (!token) return;
    const res = http.get(`${BASE_URL}/meetings/validate-invite?token=${token}`, {
        headers: authHeaders(),
        tags: { name: "GET /meetings/validate-invite" },
    });

    check(res, {
        "GET validate-invite: not 5xx": (r) => r.status < 500,
    });
}

