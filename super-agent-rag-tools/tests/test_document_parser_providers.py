import base64
import json
import unittest
from unittest.mock import patch

from fastapi import HTTPException

from rag_tools import document_parser
from rag_tools.document_parser import parse_document
from rag_tools.schemas.document_parse import DocumentParseRequest


class AliyunDocMindDocumentParserTest(unittest.TestCase):
    def test_document_parser_status_exposes_fixed_type_routes(self) -> None:
        status = document_parser.document_parser_status()

        self.assertEqual("type_routed", status["defaultProvider"])
        self.assertEqual(2, len(status["providers"]))
        providers = {provider["providerName"]: provider for provider in status["providers"]}
        self.assertIn("native_text", providers)
        self.assertIn("aliyun_docmind", providers)
        self.assertIn("md", providers["native_text"]["supportedFileTypes"])
        self.assertIn("txt", providers["native_text"]["supportedFileTypes"])
        self.assertIn("html", providers["native_text"]["supportedFileTypes"])
        self.assertIn("pdf", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("png", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("jpg", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("jpeg", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("ocr", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("layout", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("table", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("figure", providers["aliyun_docmind"]["capabilities"])

    def test_markdown_uses_native_text_parser_without_docmind_credentials(self) -> None:
        request = DocumentParseRequest(
            fileName="星联智服全渠道客服平台上线与运营管理手册.md",
            fileType="MD",
            contentBase64=base64.b64encode(
                "# 上线手册\n\n"
                "## 灰度发布\n\n"
                "蓝桥订单 7391 需要在 AuditTrail 中留痕。\n\n"
                "| 项目 | 负责人 |\n"
                "| --- | --- |\n"
                "| 支付回调延迟 | SRE 值班长 |\n".encode("utf-8")
            ).decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "parse", side_effect=AssertionError("Document Mind should not parse Markdown")):
            response = parse_document(request)

        self.assertEqual("native_text", response.provider_name)
        self.assertIn("markdown", response.capabilities)
        self.assertIn("蓝桥订单 7391", response.parsed_text)
        self.assertIn("AuditTrail", response.parsed_text)
        self.assertIn("上线手册", response.structure_nodes[1].title)
        artifact_types = {artifact.artifact_type for artifact in response.artifacts}
        self.assertNotIn("ALIYUN_DOCMIND_JSON", artifact_types)
        self.assertNotIn("LAYOUT_JSON", artifact_types)
        self.assertIn("MARKDOWN", artifact_types)
        self.assertIn("JSON", artifact_types)

    def test_missing_credential_fails_without_fallback(self) -> None:
        request = DocumentParseRequest(
            fileName="sample.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(b"%PDF-1.4\n%%EOF").decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""):
            with self.assertRaises(HTTPException) as context:
                parse_document(request)

        self.assertEqual(503, context.exception.status_code)
        self.assertIn("阿里云 Document Mind", str(context.exception.detail))

    def test_image_uses_docmind_without_fallback(self) -> None:
        request = DocumentParseRequest(
            fileName="scan.png",
            fileType="PNG",
            contentBase64=base64.b64encode(b"\x89PNG\r\n\x1a\n").decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""):
            with self.assertRaises(HTTPException) as context:
                parse_document(request)

        self.assertEqual(503, context.exception.status_code)
        self.assertIn("阿里云 Document Mind", str(context.exception.detail))

    def test_docmind_maps_layouts_to_standard_blocks(self) -> None:
        request = DocumentParseRequest(
            fileName="docmind.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(b"%PDF-1.4\n%%EOF").decode("ascii"),
        )
        fake_client = _FakeDocMindClient()

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value="ak"), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value="sk"), \
                patch.object(document_parser.AliyunDocMindParser, "_client", return_value=fake_client), \
                patch.object(document_parser.AliyunDocMindParser, "_runtime_options", return_value=None):
            response = parse_document(request)

        self.assertEqual("aliyun_docmind", response.provider_name)
        self.assertIn("ocr", response.capabilities)
        self.assertIn("layout", response.capabilities)
        self.assertEqual(["TITLE", "TABLE", "FIGURE"], [block.block_type for block in response.blocks])
        self.assertEqual("年度经营摘要", response.blocks[0].text)
        self.assertEqual([["指标", "数值"], ["收入", "100"]], response.blocks[1].table_rows)
        self.assertIn("收入", response.blocks[1].text)
        self.assertEqual(1, response.blocks[1].page_no)
        self.assertTrue(response.blocks[1].bbox_json)

        table_metadata = json.loads(response.blocks[1].metadata_json)
        self.assertEqual("table", table_metadata.get("layoutType"))
        self.assertEqual("aliyun_docmind", table_metadata.get("providerName"))
        self.assertTrue(table_metadata.get("tableCellMetadata"))

        artifact_types = {artifact.artifact_type for artifact in response.artifacts}
        self.assertIn("ALIYUN_DOCMIND_JSON", artifact_types)
        self.assertIn("LAYOUT_JSON", artifact_types)
        self.assertIn("JSON", artifact_types)
        self.assertIn("MARKDOWN", artifact_types)
        raw_payload = self._artifact_payload(response.artifacts, "ALIYUN_DOCMIND_JSON")
        self.assertEqual("docmind-job-1", raw_payload.get("jobId"))

    def _artifact_payload(self, artifacts, artifact_type: str) -> dict:
        matches = [artifact for artifact in artifacts if artifact.artifact_type == artifact_type]
        self.assertEqual(1, len(matches))
        return json.loads(base64.b64decode(matches[0].content_base64).decode("utf-8"))


class _FakeTeaBody:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def to_map(self) -> dict:
        return self.payload


class _FakeTeaResponse:
    def __init__(self, payload: dict) -> None:
        self.body = _FakeTeaBody(payload)


class _FakeDocMindClient:
    def submit_doc_parser_job_advance(self, request, runtime):
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {"Id": "docmind-job-1"},
        })

    def query_doc_parser_status(self, request):
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {"Status": "Success", "PageCountEstimate": 1},
        })

    def get_doc_parser_result(self, request):
        layout_num = getattr(request, "layout_num", 0) or 0
        if layout_num > 0:
            return _FakeTeaResponse({"Code": "200", "Data": {"layouts": []}})
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {
                "markdownContent": "# 年度经营摘要\n\n| 指标 | 数值 |\n| --- | --- |\n| 收入 | 100 |",
                "layouts": [
                    {
                        "index": 1,
                        "type": "title",
                        "text": "年度经营摘要",
                        "pageNum": 1,
                        "layoutConf": 0.98,
                        "pos": {"x0": 12, "y0": 24, "x1": 260, "y1": 60},
                    },
                    {
                        "index": 2,
                        "type": "table",
                        "llmResult": "<table><tr><td>指标</td><td>数值</td></tr><tr><td>收入</td><td>100</td></tr></table>",
                        "pageNum": 1,
                        "layoutConf": 0.95,
                        "pos": {"x": 16, "y": 80, "width": 300, "height": 120},
                        "cells": [
                            {"rowIndex": 1, "colIndex": 1, "text": "指标", "pos": {"x0": 16, "y0": 80, "x1": 100, "y1": 110}},
                            {"rowIndex": 1, "colIndex": 2, "text": "数值", "pos": {"x0": 100, "y0": 80, "x1": 180, "y1": 110}},
                            {"rowIndex": 2, "colIndex": 1, "text": "收入", "pos": {"x0": 16, "y0": 110, "x1": 100, "y1": 140}},
                            {"rowIndex": 2, "colIndex": 2, "text": "100", "pos": {"x0": 100, "y0": 110, "x1": 180, "y1": 140}},
                        ],
                    },
                    {
                        "index": 3,
                        "type": "figure",
                        "text": "收入趋势图显示持续增长。",
                        "pageNum": 1,
                        "pos": {"left": 20, "top": 220, "right": 260, "bottom": 320},
                    },
                ],
            },
        })


if __name__ == "__main__":
    unittest.main()
