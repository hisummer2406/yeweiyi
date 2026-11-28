import { Input } from 'antd';
import { useState } from 'react';
import { normalizeKeyword } from '@/utils/listSearch';

export const PAGE_SIZE = 20;

/**
 * 资产管理列表的关键词与页码（#108）：提交或清空关键词都回到第 1 页。
 * 页码受控，传给 ProTable 的 pagination；表格用 dataSource 显示前端过滤后的行。
 */
export function useKeywordSearch() {
  const [keyword, setKeyword] = useState('');
  const [current, setCurrent] = useState(1);
  return {
    keyword,
    /** 关键词去空格后非空 */
    searching: normalizeKeyword(keyword) !== '',
    onSearch: (v: string) => {
      setKeyword(v);
      setCurrent(1);
    },
    pagination: {
      current,
      pageSize: PAGE_SIZE,
      showSizeChanger: false,
      onChange: (page: number) => setCurrent(page),
    },
  };
}

/** 列表工具栏左侧的搜索框：回车/按钮提交，清空按钮立即恢复完整列表 */
export function KeywordSearchBox({
  placeholder,
  onSearch,
}: {
  placeholder: string;
  onSearch: (v: string) => void;
}) {
  return (
    <Input.Search
      allowClear
      style={{ width: 280 }}
      placeholder={placeholder}
      onSearch={onSearch}
    />
  );
}
