from pathlib import Path

from playwright.sync_api import sync_playwright


ARTIFACT_DIR = Path(__file__).resolve().parent / "default-location"
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)


def fulfill_json(route, data):
    route.fulfill(
        status=200,
        content_type="application/json",
        body=data,
    )


def main():
    discovery_urls = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 960},
            geolocation={"longitude": 112.997033, "latitude": 28.115606},
            permissions=["geolocation"],
        )
        page = context.new_page()
        console_messages = []
        page.on("console", lambda msg: console_messages.append(f"{msg.type}: {msg.text}"))
        page.on("pageerror", lambda error: console_messages.append(f"pageerror: {error}"))

        page.route(
            "**/api/shop-type/list",
            lambda route: fulfill_json(
                route,
                """
                {
                  "success": true,
                  "data": [
                    { "id": 1, "name": "美食", "icon": "/icons/default-icon.png" }
                  ]
                }
                """,
            ),
        )
        page.route(
            "**/api/blog/hot**",
            lambda route: fulfill_json(
                route,
                """
                {
                  "success": true,
                  "data": [
                    {
                      "id": 10,
                      "title": "当前热笔记",
                      "images": "/icons/default-icon.png",
                      "icon": "/icons/default-icon.png",
                      "name": "测试用户",
                      "liked": 3,
                      "isLike": false
                    }
                  ]
                }
                """,
            ),
        )

        def discovery(route):
            discovery_urls.append(route.request.url)
            fulfill_json(
                route,
                """
                {
                  "success": true,
                  "data": [
                    {
                      "source": "amap",
                      "sourceId": "B002",
                      "orderable": false,
                      "typeName": "餐饮服务;中餐厅",
                      "name": "默认定位附近店",
                      "area": "雨花区",
                      "address": "测试路 1 号",
                      "distance": 28,
                      "score": 45,
                      "comments": 12,
                      "avgPrice": 52
                    }
                  ]
                }
                """,
            )

        page.route("**/api/shop/discovery**", discovery)

        page.goto("http://localhost:5183/")
        page.wait_for_load_state("networkidle")
        page.wait_for_function(
            "() => sessionStorage.getItem('citycopilot:selected-location')?.includes('当前位置')"
        )
        home_text = page.locator("body").inner_text()
        assert "当前位置" in home_text
        assert "选择消费位置" not in home_text
        assert page.locator(".city-topbar .city-button").count() == 0
        assert page.locator(".home-command .consumer-location-clear").count() == 0
        selector_class = page.locator(".home-command .consumer-location-selector").get_attribute("class") or ""
        assert "consumer-location-selector-light" in selector_class
        assert "consumer-location-selector-dark" not in selector_class
        title_color = page.eval_on_selector(
            ".home-command .consumer-location-main strong",
            "el => getComputedStyle(el).color",
        )
        assert title_color != "rgb(255, 253, 247)"

        page.goto("http://localhost:5183/shops")
        page.wait_for_load_state("networkidle")
        for _ in range(30):
            if any("x=112.997033" in url and "y=28.115606" in url for url in discovery_urls):
                break
            page.wait_for_timeout(100)
        else:
            raise AssertionError(f"shop discovery was not called with geolocation: {discovery_urls}")

        list_text = page.locator("body").inner_text()
        assert "默认定位附近店" in list_text
        assert "当前位置" in list_text
        assert page.locator(".consumer-location-clear").count() == 0

        (ARTIFACT_DIR / "page.html").write_text(page.content(), encoding="utf-8")
        (ARTIFACT_DIR / "console.txt").write_text("\n".join(console_messages), encoding="utf-8")
        (ARTIFACT_DIR / "discovery-urls.txt").write_text("\n".join(discovery_urls), encoding="utf-8")
        page.screenshot(path=str(ARTIFACT_DIR / "shop-list.png"), full_page=True)

        context.close()
        browser.close()


if __name__ == "__main__":
    main()
