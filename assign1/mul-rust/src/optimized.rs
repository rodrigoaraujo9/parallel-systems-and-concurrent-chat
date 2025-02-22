pub fn final_mul_line(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
    // verificar que os dados são aceites e inicializar as variáveis

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

    // fazer a transposta do B para melhorar a localidade da cache
    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }

    // multiplicação das matrizes tendo enconta a transposta de B
    for (i, a_row) in a.chunks_exact(side).enumerate() {
        // itera sobre as linhas de A
        let res_row = &mut res[i * side..(i + 1) * side]; // extrai a row _i_ do [res]

        for k in 0..side {
            // itera sobre colunas de A / linhas de B.
            let a_val = a_row[k]; // a[i][k];
            let b_trans_row = &b_transposed[k * side..(k + 1) * side]; // b[k][j] -> linha k do b (pre-transposto)

            // multiplicação e acumulação dos valores selecionados
            res_row
                .iter_mut()
                .zip(b_trans_row.iter())
                .for_each(|(r, &b_val)| {
                    *r += a_val * b_val;
                });
        }
    }

    Some(res)
}

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

pub fn final_mul_block(a: &[f64], b: &[f64], bk_size: usize) -> Option<Vec<f64>> {
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

    let mut b_transposed = vec![0.0; length];
    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }

    for ii in (0..side).step_by(bk_size) {
        for jj in (0..side).step_by(bk_size) {
            for kk in (0..side).step_by(bk_size) {
                for i in ii..(ii + bk_size).min(side) {
                    for k in kk..(kk + bk_size).min(side) {
                        let a_val = a[i * side + k];
                        let b_row = &b_transposed[k * side..(k + 1) * side];
                        let res_row = &mut res[i * side..(i + 1) * side];
                        for j in jj..(jj + bk_size).min(side) {
                            res_row[j] += a_val * b_row[j];
                        }
                    }
                }
            }
        }
    }

    Some(res)
}
