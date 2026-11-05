/** 客户名称上限（字符数，汉字、字母都算 1 个）。这是界面限制，云端上限是 100 个字符。 */
export const CUSTOMER_NAME_MAX = 20;

/** 按 Unicode 字符计数（emoji 等算 1 个，不是 UTF-16 单元）。 */
export const customerNameLength = (name: string): number => [...name].length;

/** 传给 antd Input 的 `count`：显示 n/20，超出时截断，输入法组合期间不误截。 */
export const customerNameCount = {
  max: CUSTOMER_NAME_MAX,
  strategy: customerNameLength,
  exceedFormatter: (value: string, { max }: { max: number }) =>
    [...value].slice(0, max).join(''),
};
