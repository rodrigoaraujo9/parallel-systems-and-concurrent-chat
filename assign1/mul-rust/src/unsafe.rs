pub fn unsafe_mul_line(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }

    let length = a.len();
    let side_f64 = (length as f64).sqrt();

    if side_f64.fract() != 0.0 {
        return None;
    }

    let side = side_f64 as usize;
    let mut res = vec![0.0; length];

    // Transpose matrix B to improve cache locality
    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            unsafe {
                *b_transposed.get_unchecked_mut(j * side + i) = *b.get_unchecked(i * side + j);
            }
        }
    }

    // Matrix multiplication using transposed B
    for i in 0..side {
        let res_row = &mut res[i * side..(i + 1) * side];
        let a_row = &a[i * side..(i + 1) * side];

        for k in 0..side {
            let a_val = unsafe { *a_row.get_unchecked(k) };

            for j in 0..side {
                unsafe {
                    *res_row.get_unchecked_mut(j) +=
                        a_val * *b_transposed.get_unchecked(k * side + j);
                }
            }
        }
    }

    Some(res)
}
